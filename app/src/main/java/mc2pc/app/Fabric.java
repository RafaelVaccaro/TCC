package mc2pc.app;

import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.TlsChannelCredentials;
import mc2pc.app.Topology.Org;
import mc2pc.common.model.Trust;
import org.hyperledger.fabric.client.CommitException;
import org.hyperledger.fabric.client.Contract;
import org.hyperledger.fabric.client.Gateway;
import org.hyperledger.fabric.client.GatewayException;
import org.hyperledger.fabric.client.Hash;
import org.hyperledger.fabric.client.Status;
import org.hyperledger.fabric.client.SubmittedTransaction;
import org.hyperledger.fabric.client.Transaction;
import org.hyperledger.fabric.client.identity.Identities;
import org.hyperledger.fabric.client.identity.Signers;
import org.hyperledger.fabric.client.identity.X509Identity;
import org.hyperledger.fabric.protos.gateway.ErrorDetail;
import org.hyperledger.fabric.protos.gateway.PreparedTransaction;
import org.hyperledger.fabric.protos.peer.TxValidationCode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Conexão via Fabric Gateway e utilitários (submit, evaluate, provas, identidades). */
public final class Fabric implements AutoCloseable {

    private final ManagedChannel grpc;
    private final Gateway gateway;

    private Fabric(ManagedChannel grpc, Gateway gateway) {
        this.grpc = grpc;
        this.gateway = gateway;
    }

    /** Conecta ao peer0 da org como o usuário dado ("Admin" ou "User1"). */
    public static Fabric connect(Org org, String user) {
        try {
            Path tlsCa = orgDir(org).resolve("peers/" + org.peerHost() + "/tls/ca.crt");
            ManagedChannel grpc = Grpc.newChannelBuilder("localhost:" + org.port(),
                            TlsChannelCredentials.newBuilder().trustManager(tlsCa.toFile()).build())
                    .overrideAuthority(org.peerHost()).build();
            Gateway gw = Gateway.newInstance()
                    .identity(new X509Identity(org.mspId(), Identities.readX509Certificate(certPem(org, user))))
                    .signer(Signers.newPrivateKeySigner(privateKey(org, user)))
                    .hash(Hash.SHA256)
                    .connection(grpc)
                    .evaluateOptions(o -> o.withDeadlineAfter(10, TimeUnit.SECONDS))
                    .endorseOptions(o -> o.withDeadlineAfter(30, TimeUnit.SECONDS))
                    .submitOptions(o -> o.withDeadlineAfter(10, TimeUnit.SECONDS))
                    .commitStatusOptions(o -> o.withDeadlineAfter(60, TimeUnit.SECONDS))
                    .connect();
            return new Fabric(grpc, gw);
        } catch (Exception e) {
            throw new IllegalStateException("falha ao conectar em " + org.peerHost() + ": " + e.getMessage(), e);
        }
    }

    public Contract contract(String channel, String chaincode) {
        return gateway.getNetwork(channel).getContract(chaincode);
    }

    public org.hyperledger.fabric.client.Network network(String channel) {
        return gateway.getNetwork(channel);
    }

    @Override
    public void close() throws InterruptedException {
        gateway.close();
        grpc.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------ transações

    /** Resultado de uma submissão já commitada. */
    public record Committed(String result, long blockNumber, String txId) {
    }

    /** Endossa, envia ao orderer e espera o commit; repete em conflitos MVCC. */
    public static Committed submit(Contract c, String fn, String... args) {
        for (int attempt = 1; ; attempt++) {
            try {
                SubmittedTransaction tx = c.newProposal(fn).addArguments(args).build().endorse().submitAsync();
                Status st = tx.getStatus();
                if (st.isSuccessful()) {
                    return new Committed(new String(tx.getResult(), StandardCharsets.UTF_8), st.getBlockNumber(),
                            st.getTransactionId());
                }
                boolean retryable = st.getCode() == TxValidationCode.MVCC_READ_CONFLICT
                        || st.getCode() == TxValidationCode.PHANTOM_READ_CONFLICT;
                if (!retryable || attempt >= 5) {
                    throw new IllegalStateException(fn + ": transação inválida (" + st.getCode() + ")");
                }
                log("  ↻ %s: %s, tentando de novo (%d)", fn, st.getCode(), attempt);
                Thread.sleep(300L * attempt);
            } catch (GatewayException e) {
                throw new IllegalStateException(fn + ": " + describe(e), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    public static String evaluate(Contract c, String fn, String... args) {
        try {
            return new String(c.evaluateTransaction(fn, args), StandardCharsets.UTF_8);
        } catch (GatewayException e) {
            throw new IllegalStateException(fn + ": " + describe(e), e);
        }
    }

    /** Prova = consulta endossada (não submetida). proof é o Envelope em base64; result, a resposta. */
    public record Endorsed(String proof, String result) {
    }

    public static Endorsed prove(Contract c, String fn, String... args) {
        try {
            Transaction tx = c.newProposal(fn).addArguments(args).build().endorse();
            PreparedTransaction pt = PreparedTransaction.parseFrom(tx.getBytes());
            return new Endorsed(Base64.getEncoder().encodeToString(pt.getEnvelope().toByteArray()),
                    new String(tx.getResult(), StandardCharsets.UTF_8));
        } catch (GatewayException e) {
            throw new IllegalStateException(fn + " (prova): " + describe(e), e);
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
            throw new IllegalStateException(e);
        }
    }

    static String describe(Exception e) {
        if (e instanceof GatewayException ge && !ge.getDetails().isEmpty()) {
            return ge.getDetails().stream().map(ErrorDetail::getMessage).distinct().collect(Collectors.joining("; "));
        }
        if (e instanceof CommitException ce) {
            return "commit " + ce.getCode();
        }
        return e.getMessage();
    }

    // ------------------------------------------------------------------ identidades / cripto

    /** Raiz do material criptográfico (cryptogen): $MC2PC_ROOT/network/organizations. */
    static Path cryptoRoot() {
        String root = System.getenv().getOrDefault("MC2PC_ROOT", ".");
        return Path.of(root, "network", "organizations");
    }

    static Path orgDir(Org org) {
        return cryptoRoot().resolve("peerOrganizations").resolve(org.domain());
    }

    static Path mspDir(Org org, String user) {
        return orgDir(org).resolve("users/" + user + "@" + org.domain() + "/msp");
    }

    public static String certPem(Org org, String user) {
        return read(single(mspDir(org, user).resolve("signcerts")));
    }

    public static PrivateKey privateKey(Org org, String user) {
        try {
            return Identities.readPrivateKey(read(single(mspDir(org, user).resolve("keystore"))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** CA de identidade (msp/cacerts) da org — base da confiança nas provas e nas assinaturas. */
    public static String caCertPem(Org org) {
        return read(single(orgDir(org).resolve("msp/cacerts")));
    }

    public static List<Trust.OrgTrust> trustOf(List<Org> orgs) {
        return orgs.stream().map(o -> new Trust.OrgTrust(o.mspId(), List.of(caCertPem(o)))).toList();
    }

    /** Assinatura ECDSA (DER, base64) sobre SHA-256(msg) — a parte "assina a intenção". */
    public static String sign(PrivateKey key, byte[] msg) {
        try {
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initSign(key);
            s.update(msg);
            return Base64.getEncoder().encodeToString(s.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path single(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.findFirst().orElseThrow(() -> new IllegalStateException("vazio: " + dir));
        } catch (IOException e) {
            throw new UncheckedIOException("não encontrado: " + dir + " (rodou ./scripts/network.sh up?)", e);
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ log

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    public static synchronized void log(String fmt, Object... args) {
        System.out.println(LocalTime.now().format(TS) + " " + String.format(fmt, args));
    }
}
