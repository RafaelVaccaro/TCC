package mc2pc.app;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mc2pc.common.model.Gtx;
import mc2pc.common.model.Json;
import mc2pc.common.model.States;
import mc2pc.common.model.Sub;
import mc2pc.common.model.Trust;
import org.hyperledger.fabric.client.ChaincodeEvent;
import org.hyperledger.fabric.client.CloseableIterator;
import org.hyperledger.fabric.client.Contract;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static mc2pc.app.Fabric.evaluate;
import static mc2pc.app.Fabric.log;
import static mc2pc.app.Fabric.prove;
import static mc2pc.app.Fabric.submit;
import static mc2pc.app.Topology.MASTER_CHANNEL;
import static mc2pc.app.Topology.ORGCOORD;
import static mc2pc.app.Topology.TXMANAGER;

/**
 * Relayer: escuta eventos do txmanager na masterchain e conduz o protocolo, levando provas
 * (consultas endossadas) de uma cadeia para outra. Não é confiável: tudo que entrega é verificado on-chain.
 *
 * <pre>
 * Begin   → Fase 2: GetSub (P1) → Prepare em cada cadeia (paralelo)
 *           Fase 3: GetStage (P2) → RecordVote; Decide
 * Decided → Fase 5: GetDecision (P3) → Commit/Abort em cada cadeia (paralelo)
 *           Fase 6: GetStage (P4) → RecordCompletion
 * </pre>
 */
public final class Relayer {

    /** Cadastro de cadeia como devolvido por GetChain. */
    static class ChainReg {
        String chainId;
        Trust trust;
    }

    private final Fabric fabric;
    private final Contract tm;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Map<String, Object> gtxLocks = new ConcurrentHashMap<>();
    private final Map<String, ChainReg> chains = new ConcurrentHashMap<>();

    private Relayer(Fabric fabric) {
        this.fabric = fabric;
        this.tm = fabric.contract(MASTER_CHANNEL, TXMANAGER);
    }

    public static void run() throws Exception {
        try (Fabric f = Fabric.connect(ORGCOORD, "User1")) {
            new Relayer(f).listen();
        }
    }

    private void listen() {
        log("relayer escutando eventos de %s/%s ...", MASTER_CHANNEL, TXMANAGER);
        try (CloseableIterator<ChaincodeEvent> events = fabric.network(MASTER_CHANNEL).getChaincodeEvents(TXMANAGER)) {
            events.forEachRemaining(ev -> {
                JsonObject p = JsonParser.parseString(new String(ev.getPayload(), StandardCharsets.UTF_8)).getAsJsonObject();
                String gtxId = p.get("gtxId").getAsString();
                log("[evento] %s %s (bloco %d)", ev.getEventName(), p, ev.getBlockNumber());
                switch (ev.getEventName()) {
                    case "Begin" -> async(gtxId, () -> onBegin(gtxId));
                    case "Decided" -> async(gtxId, () -> onDecided(gtxId, p.get("decisao").getAsString()));
                    default -> {
                    }
                }
            });
        }
    }

    /** Processa em background, serializando por GTX. */
    private void async(String gtxId, Runnable r) {
        pool.submit(() -> {
            synchronized (gtxLocks.computeIfAbsent(gtxId, k -> new Object())) {
                try {
                    r.run();
                } catch (Exception e) {
                    log("[%s] ERRO: %s", gtxId, e.getMessage());
                }
            }
        });
    }

    // Fases 2, 3 e 4
    private void onBegin(String gtxId) {
        Gtx gtx = Json.from(evaluate(tm, "GetGtx", gtxId), Gtx.class);

        // Fase 2: Prepare em cada cadeia participante (em paralelo)
        parallel(gtx.participantes, chain -> {
            Fabric.Endorsed p1 = prove(tm, "GetSub", gtxId, chain);
            Sub sub = Json.from(p1.result(), Sub.class);
            log("[%s] P1 GetSub(%s) endossada → Prepare em %s", gtxId, chain, reg(chain).trust.channel);
            String r = submit(participant(chain), "Prepare", gtxId, Json.to(sub.ops), p1.proof()).result();
            log("[%s] %s.Prepare → %s", gtxId, chain, r);
        });

        // Fase 3: votos (sequencial na masterchain)
        for (String chain : gtx.participantes) {
            Fabric.Endorsed p2 = prove(participant(chain), "GetStage", gtxId);
            log("[%s] P2 GetStage(%s) = %s → RecordVote", gtxId, chain, p2.result());
            submit(tm, "RecordVote", gtxId, chain, p2.proof());
        }

        // Fase 4: decisão (o evento Decided dispara a fase 5)
        log("[%s] Decide → %s", gtxId, submit(tm, "Decide", gtxId).result());
    }

    // Fases 5 e 6
    private void onDecided(String gtxId, String decisao) {
        Gtx gtx = Json.from(evaluate(tm, "GetGtx", gtxId), Gtx.class);
        if (gtx.concluida) {
            return;
        }
        Fabric.Endorsed p3 = prove(tm, "GetDecision", gtxId);
        String fn = States.DEC_COMMIT.equals(decisao) ? "Commit" : "Abort";
        log("[%s] P3 GetDecision = %s → %s nas cadeias", gtxId, p3.result(), fn);

        // Fase 5: Commit/Abort em cada cadeia (em paralelo)
        parallel(gtx.participantes, chain ->
                log("[%s] %s.%s → %s", gtxId, chain, fn, submit(participant(chain), fn, gtxId, p3.proof()).result()));

        // Fase 6: conclusão (sequencial na masterchain)
        for (String chain : gtx.participantes) {
            Fabric.Endorsed p4 = prove(participant(chain), "GetStage", gtxId);
            log("[%s] P4 GetStage(%s) = %s → RecordCompletion", gtxId, chain, p4.result());
            submit(tm, "RecordCompletion", gtxId, chain, p4.proof());
        }
    }

    private void parallel(List<String> chains, java.util.function.Consumer<String> task) {
        CompletableFuture.allOf(chains.stream()
                .map(c -> CompletableFuture.runAsync(() -> task.accept(c), pool))
                .toArray(CompletableFuture[]::new)).join();
    }

    private ChainReg reg(String chainId) {
        return chains.computeIfAbsent(chainId, id -> Json.from(evaluate(tm, "GetChain", id), ChainReg.class));
    }

    private Contract participant(String chainId) {
        ChainReg r = reg(chainId);
        return fabric.contract(r.trust.channel, r.trust.chaincode);
    }
}
