package mc2pc.common.cc;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import mc2pc.common.model.Trust;
import org.hyperledger.fabric.protos.common.ChannelHeader;
import org.hyperledger.fabric.protos.common.Envelope;
import org.hyperledger.fabric.protos.common.HeaderType;
import org.hyperledger.fabric.protos.common.Payload;
import org.hyperledger.fabric.protos.msp.SerializedIdentity;
import org.hyperledger.fabric.protos.peer.ChaincodeAction;
import org.hyperledger.fabric.protos.peer.ChaincodeActionPayload;
import org.hyperledger.fabric.protos.peer.ChaincodeInvocationSpec;
import org.hyperledger.fabric.protos.peer.ChaincodeProposalPayload;
import org.hyperledger.fabric.protos.peer.Endorsement;
import org.hyperledger.fabric.protos.peer.ProposalResponsePayload;
import org.hyperledger.fabric.protos.peer.Transaction;
import org.hyperledger.fabric.shim.ChaincodeException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * Verifica "provas" de estado entre cadeias.
 *
 * <p>Uma prova é o resultado de uma CONSULTA ENDOSSADA em outra cadeia: o relayer chama
 * {@code endorse()} via Fabric Gateway numa função de leitura (GetSub, GetStage, GetDecision)
 * e, em vez de submeter ao orderer, entrega o envelope (base64) ao chaincode da cadeia de destino.
 *
 * <p>O envelope traz o canal (ChannelHeader), chaincode/função/argumentos (ChaincodeProposalPayload),
 * a resposta do chaincode (ProposalResponsePayload, amarrada à proposta pelo hash) e os endossos —
 * assinaturas dos peers sobre {@code ProposalResponsePayload || endorser}. Tudo é reconferido aqui
 * contra o {@link Trust} da cadeia de origem, como faria o validador do Fabric.
 */
public final class Proof {
    private Proof() {
    }

    /** Conteúdo autenticado de uma prova. */
    public record Result(String function, List<String> args, byte[] payload, List<String> endorsers) {
        public String payloadString() {
            return new String(payload, StandardCharsets.UTF_8);
        }

        public Result expect(String fn, String... expectedArgs) {
            if (!function.equals(fn)) {
                throw new ChaincodeException("prova: função '" + function + "', esperada '" + fn + "'");
            }
            if (!args.equals(Arrays.asList(expectedArgs))) {
                throw new ChaincodeException("prova: argumentos " + args + ", esperados " + Arrays.toString(expectedArgs));
            }
            return this;
        }
    }

    /** Confere a configuração de confiança (certificados legíveis, política aplicável). */
    public static void validate(Trust t) {
        if (isBlank(t.channel) || isBlank(t.chaincode)) {
            throw new ChaincodeException("channel e chaincode são obrigatórios");
        }
        if (t.orgs == null || t.orgs.isEmpty()) {
            throw new ChaincodeException("pelo menos uma org é obrigatória");
        }
        List<String> seen = new ArrayList<>();
        for (Trust.OrgTrust o : t.orgs) {
            if (isBlank(o.mspId) || o.rootCerts == null || o.rootCerts.isEmpty() || seen.contains(o.mspId)) {
                throw new ChaincodeException("org inválida ou duplicada: " + o.mspId);
            }
            seen.add(o.mspId);
            o.rootCerts.forEach(Crypto::parseCert);
        }
        required(t);
    }

    /**
     * @param now determinístico (timestamp da transação) — usado na validade dos certificados.
     */
    public static Result verify(String proofB64, Trust trust, Instant now) {
        try {
            return doVerify(proofB64, trust, now);
        } catch (InvalidProtocolBufferException e) {
            throw new ChaincodeException("prova: protobuf inválido: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ChaincodeException("prova: " + e.getMessage());
        }
    }

    private static Result doVerify(String proofB64, Trust trust, Instant now) throws InvalidProtocolBufferException {
        Envelope env = Envelope.parseFrom(Base64.getDecoder().decode(proofB64));
        Payload payload = Payload.parseFrom(env.getPayload());
        ChannelHeader chdr = ChannelHeader.parseFrom(payload.getHeader().getChannelHeader());
        if (chdr.getType() != HeaderType.ENDORSER_TRANSACTION_VALUE) {
            throw new ChaincodeException("prova: tipo de header inesperado " + chdr.getType());
        }
        if (!chdr.getChannelId().equals(trust.channel)) {
            throw new ChaincodeException("prova: canal '" + chdr.getChannelId() + "', esperado '" + trust.channel + "'");
        }

        Transaction tx = Transaction.parseFrom(payload.getData());
        if (tx.getActionsCount() != 1) {
            throw new ChaincodeException("prova: transação deve ter exatamente uma ação");
        }
        ChaincodeActionPayload cap = ChaincodeActionPayload.parseFrom(tx.getActions(0).getPayload());

        // Proposta: chaincode, função e argumentos
        ChaincodeProposalPayload cpp = ChaincodeProposalPayload.parseFrom(cap.getChaincodeProposalPayload());
        ChaincodeInvocationSpec cis = ChaincodeInvocationSpec.parseFrom(cpp.getInput());
        String ccName = cis.getChaincodeSpec().getChaincodeId().getName();
        if (!ccName.equals(trust.chaincode)) {
            throw new ChaincodeException("prova: chaincode '" + ccName + "', esperado '" + trust.chaincode + "'");
        }
        List<ByteString> rawArgs = cis.getChaincodeSpec().getInput().getArgsList();
        if (rawArgs.isEmpty()) {
            throw new ChaincodeException("prova: sem função");
        }

        // Resposta endossada, amarrada à proposta pelo hash (mesma regra do validador do Fabric)
        ByteString prpBytes = cap.getAction().getProposalResponsePayload();
        ProposalResponsePayload prp = ProposalResponsePayload.parseFrom(prpBytes);
        MessageDigest sha = sha256();
        sha.update(payload.getHeader().getChannelHeader().toByteArray());
        sha.update(payload.getHeader().getSignatureHeader().toByteArray());
        sha.update(cap.getChaincodeProposalPayload().toByteArray());
        if (!MessageDigest.isEqual(sha.digest(), prp.getProposalHash().toByteArray())) {
            throw new ChaincodeException("prova: hash da proposta não confere");
        }
        ChaincodeAction action = ChaincodeAction.parseFrom(prp.getExtension());
        if (!action.getChaincodeId().getName().equals(trust.chaincode)) {
            throw new ChaincodeException("prova: chaincode da resposta não confere");
        }
        if (action.getResponse().getStatus() != 200) {
            throw new ChaincodeException("prova: resposta com status " + action.getResponse().getStatus()
                    + ": " + action.getResponse().getMessage());
        }

        // Endossos: assinatura válida, certificado emitido pela CA da org, papel peer
        List<String> endorsers = new ArrayList<>();
        for (Endorsement e : cap.getAction().getEndorsementsList()) {
            String msp = verifyEndorsement(prpBytes, e, trust, now);
            if (!endorsers.contains(msp)) {
                endorsers.add(msp);
            }
        }
        int need = required(trust);
        if (endorsers.size() < need) {
            throw new ChaincodeException("prova: " + endorsers.size() + " org(s) endossaram " + endorsers
                    + ", política '" + trust.policy + "' exige " + need);
        }

        List<String> args = rawArgs.subList(1, rawArgs.size()).stream().map(ByteString::toStringUtf8).toList();
        return new Result(rawArgs.get(0).toStringUtf8(), args, action.getResponse().getPayload().toByteArray(), endorsers);
    }

    private static String verifyEndorsement(ByteString prpBytes, Endorsement e, Trust trust, Instant now)
            throws InvalidProtocolBufferException {
        SerializedIdentity sid = SerializedIdentity.parseFrom(e.getEndorser());
        Trust.OrgTrust org = trust.orgs.stream().filter(o -> o.mspId.equals(sid.getMspid())).findFirst()
                .orElseThrow(() -> new ChaincodeException("prova: MSP '" + sid.getMspid() + "' não é confiável"));
        X509Certificate cert = Crypto.verifyCert(sid.getIdBytes().toStringUtf8(), List.of(org), now);
        if (!Crypto.organizationalUnits(cert).contains("peer")) {
            throw new ChaincodeException("prova: endossante não é um peer");
        }
        Crypto.verifySignature(cert, prpBytes.concat(e.getEndorser()).toByteArray(), e.getSignature().toByteArray());
        return sid.getMspid();
    }

    private static int required(Trust t) {
        try {
            return t.required();
        } catch (IllegalArgumentException e) {
            throw new ChaincodeException(e.getMessage());
        }
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
