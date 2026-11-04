package mc2pc.common.cc;

import mc2pc.common.model.Json;
import org.hyperledger.fabric.contract.Context;
import org.hyperledger.fabric.shim.ChaincodeException;

import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.HexFormat;

/** Helpers de estado/eventos/identidade comuns aos chaincodes. */
public final class Ledger {
    private Ledger() {
    }

    /** Lê e desserializa; null se a chave não existe. */
    public static <T> T get(Context ctx, String key, Class<T> type) {
        byte[] b = ctx.getStub().getState(key);
        if (b == null || b.length == 0) {
            return null;
        }
        return Json.from(new String(b, StandardCharsets.UTF_8), type);
    }

    public static <T> T require(Context ctx, String key, Class<T> type, String what) {
        T v = get(ctx, key, type);
        if (v == null) {
            throw new ChaincodeException(what + " não encontrado(a)");
        }
        return v;
    }

    public static void put(Context ctx, String key, Object value) {
        ctx.getStub().putStringState(key, Json.to(value));
    }

    /**
     * Emite o evento da transação. ATENÇÃO ao determinismo: cada endossante roda sua própria JVM,
     * e a ordem de iteração de Map.of/HashMap pode diferir entre elas — use TreeMap ou POJOs.
     */
    public static void emit(Context ctx, String name, Object payload) {
        ctx.getStub().setEvent(name, Json.to(payload).getBytes(StandardCharsets.UTF_8));
    }

    /** "Relógio" determinístico: timestamp da proposta (igual em todos os endossantes). */
    public static Instant now(Context ctx) {
        return ctx.getStub().getTxTimestamp();
    }

    /** Exige certificado com OU=admin (NodeOUs do MSP). */
    public static void requireAdmin(Context ctx) {
        X509Certificate cert = ctx.getClientIdentity().getX509Certificate();
        if (!Crypto.organizationalUnits(cert).contains("admin")) {
            throw new ChaincodeException("apenas administradores (invocador: " + ctx.getClientIdentity().getId() + ")");
        }
    }

    public static String sha256Hex(byte[] data) {
        return HexFormat.of().formatHex(Proof.sha256().digest(data));
    }
}
