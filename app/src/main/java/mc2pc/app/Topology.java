package mc2pc.app;

import java.util.List;
import java.util.Map;

/**
 * Topologia do cenário base (2 cadeias). Para N cadeias: acrescente orgs/cadeias aqui
 * e na rede (network/*.yaml, scripts/env.sh).
 */
public final class Topology {
    private Topology() {
    }

    public record Org(String name, String mspId, int port) {
        public String domain() {
            return name + ".example.com";
        }

        public String peerHost() {
            return "peer0." + domain();
        }
    }

    public record Chain(String id, String channel, List<Org> orgs) {
    }

    public static final Org ORGCOORD = new Org("orgcoord", "OrgCoordMSP", 7051);
    public static final Org ORGA = new Org("orga", "OrgAMSP", 9051);
    public static final Org ORGB = new Org("orgb", "OrgBMSP", 11051);

    public static final String MASTER_CHANNEL = "masterchain";
    public static final List<Org> MASTER_ORGS = List.of(ORGCOORD, ORGA, ORGB);
    public static final String TXMANAGER = "txmanager";
    public static final String TWOPC = "twopc";
    /** Política exigida das provas (orgs distintas que endossam). */
    public static final String PROOF_POLICY = "MAJORITY";

    public static final List<Chain> CHAINS = List.of(
            new Chain("A", "channela", List.of(ORGCOORD, ORGA)),
            new Chain("B", "channelb", List.of(ORGCOORD, ORGB)));

    /** Partes da troca e a org que emitiu seus certificados (User1 de cada org). */
    public static final Map<String, Org> PARTIES = Map.of("Alice", ORGA, "Bob", ORGB);

    public static Chain chain(String id) {
        return CHAINS.stream().filter(c -> c.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("cadeia desconhecida: " + id));
    }
}
