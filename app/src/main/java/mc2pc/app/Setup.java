package mc2pc.app;

import mc2pc.app.Topology.Chain;
import mc2pc.common.model.Json;
import org.hyperledger.fabric.client.Contract;

import static mc2pc.app.Fabric.log;
import static mc2pc.app.Fabric.submit;
import static mc2pc.app.Topology.CHAINS;
import static mc2pc.app.Topology.MASTER_CHANNEL;
import static mc2pc.app.Topology.MASTER_ORGS;
import static mc2pc.app.Topology.ORGCOORD;
import static mc2pc.app.Topology.PROOF_POLICY;
import static mc2pc.app.Topology.TWOPC;
import static mc2pc.app.Topology.TXMANAGER;

/** Configuração inicial (como Admin@orgcoord): cadastra as cadeias, o coordenador e o estado de negócio. */
public final class Setup {
    private Setup() {
    }

    public static void run() throws Exception {
        try (Fabric f = Fabric.connect(ORGCOORD, "Admin")) {
            Contract tm = f.contract(MASTER_CHANNEL, TXMANAGER);
            String masterTrust = Json.to(Fabric.trustOf(MASTER_ORGS));

            for (Chain c : CHAINS) {
                log("masterchain.RegisterChain(%s → %s)", c.id(), c.channel());
                submit(tm, "RegisterChain", c.id(), c.channel(), TWOPC, Json.to(Fabric.trustOf(c.orgs())), PROOF_POLICY);

                Contract p = f.contract(c.channel(), TWOPC);
                log("%s.ConfigureCoordinator(%s)", c.channel(), c.id());
                submit(p, "ConfigureCoordinator", c.id(), MASTER_CHANNEL, TXMANAGER, masterTrust, PROOF_POLICY);

                seed(c, p);
            }
            log("setup concluído");
        }
    }

    /** Estado de negócio inicial do cenário do diagrama. */
    private static void seed(Chain c, Contract p) {
        switch (c.id()) {
            case "A" -> {
                log("channela: SALDO~Alice=500, SALDO~Bob=200");
                submit(p, "SetBalance", "Alice", "500");
                submit(p, "SetBalance", "Bob", "200");
            }
            case "B" -> {
                log("channelb: IMOVEL~10 dono=Bob");
                submit(p, "CreateImovel", "10", "Bob");
            }
            default -> {
            }
        }
    }
}
