package mc2pc.common;

import com.google.protobuf.ByteString;
import mc2pc.common.cc.Proof;
import mc2pc.common.model.Trust;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.hyperledger.fabric.protos.common.ChannelHeader;
import org.hyperledger.fabric.protos.common.Envelope;
import org.hyperledger.fabric.protos.common.Header;
import org.hyperledger.fabric.protos.common.HeaderType;
import org.hyperledger.fabric.protos.common.Payload;
import org.hyperledger.fabric.protos.common.SignatureHeader;
import org.hyperledger.fabric.protos.msp.SerializedIdentity;
import org.hyperledger.fabric.protos.peer.ChaincodeAction;
import org.hyperledger.fabric.protos.peer.ChaincodeActionPayload;
import org.hyperledger.fabric.protos.peer.ChaincodeEndorsedAction;
import org.hyperledger.fabric.protos.peer.ChaincodeID;
import org.hyperledger.fabric.protos.peer.ChaincodeInput;
import org.hyperledger.fabric.protos.peer.ChaincodeInvocationSpec;
import org.hyperledger.fabric.protos.peer.ChaincodeProposalPayload;
import org.hyperledger.fabric.protos.peer.ChaincodeSpec;
import org.hyperledger.fabric.protos.peer.Endorsement;
import org.hyperledger.fabric.protos.peer.ProposalResponsePayload;
import org.hyperledger.fabric.protos.peer.Response;
import org.hyperledger.fabric.protos.peer.Transaction;
import org.hyperledger.fabric.protos.peer.TransactionAction;
import org.hyperledger.fabric.shim.ChaincodeException;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProofTest {

    record TestOrg(String msp, String caPem, KeyPair peerKey, String peerPem) {
    }

    static final Instant NOW = Instant.now();

    static KeyPair ecKey() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    static X509Certificate cert(String subject, KeyPair subjectKey, String issuer, KeyPair issuerKey, boolean ca)
            throws Exception {
        var b = new JcaX509v3CertificateBuilder(new X500Name(issuer), BigInteger.valueOf(System.nanoTime()),
                Date.from(NOW.minus(Duration.ofHours(1))), Date.from(NOW.plus(Duration.ofHours(1))),
                new X500Name(subject), subjectKey.getPublic());
        b.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
        var signer = new JcaContentSignerBuilder("SHA256withECDSA").build(issuerKey.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(b.build(signer));
    }

    static String pem(X509Certificate c) throws Exception {
        return "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder().encodeToString(c.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
    }

    static TestOrg org(String msp, String ou) throws Exception {
        KeyPair caKey = ecKey();
        KeyPair peerKey = ecKey();
        String caDn = "CN=ca." + msp + ",O=" + msp;
        X509Certificate ca = cert(caDn, caKey, caDn, caKey, true);
        X509Certificate peer = cert("CN=peer0." + msp + ",OU=" + ou + ",O=" + msp, peerKey, caDn, caKey, false);
        return new TestOrg(msp, pem(ca), peerKey, pem(peer));
    }

    /** Monta um envelope com a mesma estrutura do produzido pelo Gateway em endorse(). */
    static String proof(String channel, String cc, List<String> args, String result, TestOrg... endorsers)
            throws Exception {
        ByteString chdr = ChannelHeader.newBuilder().setType(HeaderType.ENDORSER_TRANSACTION_VALUE)
                .setChannelId(channel).setTxId("tx1").build().toByteString();
        ByteString shdr = SignatureHeader.newBuilder().setCreator(ByteString.copyFromUtf8("client"))
                .setNonce(ByteString.copyFromUtf8("n")).build().toByteString();
        ChaincodeInput.Builder in = ChaincodeInput.newBuilder();
        args.forEach(a -> in.addArgs(ByteString.copyFromUtf8(a)));
        ByteString cis = ChaincodeInvocationSpec.newBuilder().setChaincodeSpec(ChaincodeSpec.newBuilder()
                .setChaincodeId(ChaincodeID.newBuilder().setName(cc)).setInput(in)).build().toByteString();
        ByteString cpp = ChaincodeProposalPayload.newBuilder().setInput(cis).build().toByteString();
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update(chdr.toByteArray());
        sha.update(shdr.toByteArray());
        sha.update(cpp.toByteArray());
        ByteString prp = ProposalResponsePayload.newBuilder().setProposalHash(ByteString.copyFrom(sha.digest()))
                .setExtension(ChaincodeAction.newBuilder().setChaincodeId(ChaincodeID.newBuilder().setName(cc))
                        .setResponse(Response.newBuilder().setStatus(200).setPayload(ByteString.copyFromUtf8(result)))
                        .build().toByteString())
                .build().toByteString();
        List<Endorsement> ends = new ArrayList<>();
        for (TestOrg o : endorsers) {
            ByteString id = SerializedIdentity.newBuilder().setMspid(o.msp)
                    .setIdBytes(ByteString.copyFromUtf8(o.peerPem)).build().toByteString();
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initSign(o.peerKey.getPrivate());
            s.update(prp.concat(id).toByteArray());
            ends.add(Endorsement.newBuilder().setEndorser(id).setSignature(ByteString.copyFrom(s.sign())).build());
        }
        ByteString capBytes = ChaincodeActionPayload.newBuilder().setChaincodeProposalPayload(cpp)
                .setAction(ChaincodeEndorsedAction.newBuilder().setProposalResponsePayload(prp).addAllEndorsements(ends))
                .build().toByteString();
        ByteString tx = Transaction.newBuilder().addActions(TransactionAction.newBuilder().setHeader(shdr)
                .setPayload(capBytes)).build().toByteString();
        ByteString payload = Payload.newBuilder().setHeader(Header.newBuilder().setChannelHeader(chdr)
                .setSignatureHeader(shdr)).setData(tx).build().toByteString();
        return Base64.getEncoder().encodeToString(Envelope.newBuilder().setPayload(payload).build().toByteArray());
    }

    static Trust trust(TestOrg... orgs) {
        Trust t = new Trust();
        t.channel = "masterchain";
        t.chaincode = "txmanager";
        t.policy = "MAJORITY";
        t.orgs = new ArrayList<>();
        for (TestOrg o : orgs) {
            t.orgs.add(new Trust.OrgTrust(o.msp, List.of(o.caPem)));
        }
        return t;
    }

    @Test
    void verificaProvas() throws Exception {
        TestOrg coord = org("OrgCoordMSP", "peer"), a = org("OrgAMSP", "peer"), b = org("OrgBMSP", "peer");
        Trust t = trust(coord, a, b);
        Proof.validate(t);
        List<String> args = List.of("GetSub", "123", "A");

        Proof.Result r = Proof.verify(proof("masterchain", "txmanager", args, "{\"ok\":1}", coord, a), t, NOW);
        r.expect("GetSub", "123", "A");
        assertEquals("{\"ok\":1}", r.payloadString());
        assertEquals(List.of("OrgCoordMSP", "OrgAMSP"), r.endorsers());
        assertThrows(ChaincodeException.class, () -> r.expect("GetSub", "999", "A"));

        TestOrg impostor = org("OrgAMSP", "peer"); // mesmo MSP, CA diferente
        TestOrg client = org("OrgBMSP", "client");
        Trust tClient = trust(coord, a, client);

        assertRejected("exige 2", proof("masterchain", "txmanager", args, "x", coord), t);
        assertRejected("exige 2", proof("masterchain", "txmanager", args, "x", coord, coord), t);
        assertRejected("canal", proof("channela", "txmanager", args, "x", coord, a), t);
        assertRejected("chaincode", proof("masterchain", "outro", args, "x", coord, a), t);
        assertRejected("não emitido", proof("masterchain", "txmanager", args, "x", coord, impostor), t);
        assertRejected("não é um peer", proof("masterchain", "txmanager", args, "x", client, coord), tClient);
        assertRejected("", "%%%", t);

        // adulterar a resposta invalida as assinaturas (mesmo tamanho para manter o protobuf válido)
        String good = proof("masterchain", "txmanager", args, "{\"estado\":\"PREPARED\"}", coord, a);
        String raw = new String(Base64.getDecoder().decode(good), java.nio.charset.StandardCharsets.ISO_8859_1);
        String tampered = Base64.getEncoder().encodeToString(
                raw.replace("PREPARED", "COMMITED").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        assertRejected("assinatura", tampered, t);

        // certificado expirado no instante da transação
        assertThrows(ChaincodeException.class, () -> Proof.verify(good, t, NOW.plus(Duration.ofDays(2))));
    }

    static void assertRejected(String msg, String proof, Trust t) {
        ChaincodeException e = assertThrows(ChaincodeException.class, () -> Proof.verify(proof, t, NOW));
        assertTrue(e.getMessage().contains(msg), () -> "esperado '" + msg + "' em: " + e.getMessage());
    }
}
