package mc2pc.app;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mc2pc.app.Topology.Chain;
import mc2pc.common.model.Gtx;
import mc2pc.common.model.Intent;
import mc2pc.common.model.Json;
import mc2pc.common.model.Leg;
import mc2pc.common.model.Op;
import mc2pc.common.model.PartySig;
import org.hyperledger.fabric.client.ChaincodeEvent;
import org.hyperledger.fabric.client.CloseableIterator;
import org.hyperledger.fabric.client.Contract;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static mc2pc.app.Fabric.evaluate;
import static mc2pc.app.Fabric.log;
import static mc2pc.app.Fabric.submit;
import static mc2pc.app.Topology.CHAINS;
import static mc2pc.app.Topology.MASTER_CHANNEL;
import static mc2pc.app.Topology.ORGCOORD;
import static mc2pc.app.Topology.PARTIES;
import static mc2pc.app.Topology.TWOPC;
import static mc2pc.app.Topology.TXMANAGER;

/** Aplicação cliente (Alice e Bob, via plataforma da OrgCoord). */
public final class Client {
    private Client() {
    }

    static final String USAGE = """
            uso: client <comando> [opções]
              swap    [--gtx ID] [--tokens 100] [--imovel 10] [--prazo 120]
                      Alice cede <tokens> na cadeia A; Bob cede o imóvel na cadeia B. Espera o Completed.
              status  --gtx ID     GTX, SUBs e STAGE em cada cadeia
              state                saldos (A) e imóvel (B)
              timeout --gtx ID     aborta por prazo expirado
              prove <canal> <chaincode> <função> [args...]
                                   imprime a prova (envelope endossado, base64) de uma consulta
            """;

    public static void run(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.print(USAGE);
            return;
        }
        Map<String, String> opt = new HashMap<>();
        for (int i = 1; i + 1 < args.length; i += 2) {
            opt.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        }
        try (Fabric f = Fabric.connect(ORGCOORD, "User1")) {
            switch (args[0]) {
                case "swap" -> swap(f, opt);
                case "status" -> status(f, need(opt, "gtx"));
                case "state" -> state(f);
                case "prove" -> System.out.println(Fabric.prove(f.contract(args[1], args[2]), args[3],
                        java.util.Arrays.copyOfRange(args, 4, args.length)).proof());
                case "timeout" -> log("Timeout → %s",
                        submit(f.contract(MASTER_CHANNEL, TXMANAGER), "Timeout", need(opt, "gtx")).result());
                default -> System.out.print(USAGE);
            }
        }
    }

    // Fase 0 + Fase 1 (Begin) e acompanhamento até Completed (passos 20/21)
    static void swap(Fabric f, Map<String, String> opt) throws Exception {
        String gtxId = opt.getOrDefault("gtx", "gtx-" + Instant.now().getEpochSecond());
        long tokens = Long.parseLong(opt.getOrDefault("tokens", "100"));
        String imovel = opt.getOrDefault("imovel", "10");
        long prazo = Long.parseLong(opt.getOrDefault("prazo", "120"));

        // Fase 0: intenção, uma perna por cadeia
        Intent intent = new Intent();
        intent.gtxId = gtxId;
        intent.deadline = Instant.now().getEpochSecond() + prazo;
        intent.legs = List.of(
                new Leg("A", List.of(Op.transferToken("Alice", "Bob", tokens))),
                new Leg("B", List.of(Op.transferImovel(imovel, "Bob", "Alice"))));
        String intentJson = Json.to(intent);
        byte[] msg = intentJson.getBytes(StandardCharsets.UTF_8);

        // Cada parte que cede algo assina a intenção com sua chave (User1 da sua org)
        Map<String, PartySig> sigs = new TreeMap<>();
        PARTIES.forEach((party, org) -> sigs.put(party,
                new PartySig(Fabric.certPem(org, "User1"), Fabric.sign(Fabric.privateKey(org, "User1"), msg))));
        log("intenção %s assinada por %s", intentJson, sigs.keySet());

        // Fase 1: Begin na masterchain
        Contract tm = f.contract(MASTER_CHANNEL, TXMANAGER);
        Fabric.Committed begin = submit(tm, "Begin", intentJson, Json.to(sigs));
        log("Begin commitado no bloco %d: %s", begin.blockNumber(), begin.result());

        // Acompanha os eventos desta GTX a partir do bloco do Begin
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        try (CloseableIterator<ChaincodeEvent> events = f.network(MASTER_CHANNEL)
                .newChaincodeEventsRequest(TXMANAGER).startBlock(begin.blockNumber()).build().getEvents()) {
            timer.schedule(events::close, prazo + 30, TimeUnit.SECONDS);
            while (events.hasNext()) {
                ChaincodeEvent ev = events.next();
                JsonObject p = JsonParser.parseString(new String(ev.getPayload(), StandardCharsets.UTF_8)).getAsJsonObject();
                if (!gtxId.equals(p.get("gtxId").getAsString())) {
                    continue;
                }
                log("[evento] %-18s %s", ev.getEventName(), p);
                if ("Completed".equals(ev.getEventName())) {
                    log("Aviso a Alice e Bob: troca concluída (%s)", p.get("decisao").getAsString());
                    break;
                }
            }
        } catch (Exception e) {
            log("parou de acompanhar: %s (veja: client status --gtx %s)", e.getMessage(), gtxId);
        } finally {
            timer.shutdownNow();
        }
        state(f);
    }

    static void status(Fabric f, String gtxId) {
        Contract tm = f.contract(MASTER_CHANNEL, TXMANAGER);
        Gtx gtx = Json.from(evaluate(tm, "GetGtx", gtxId), Gtx.class);
        log("GTX     %s", Json.to(gtx));
        for (String chain : gtx.participantes) {
            log("SUB  %s %s", chain, evaluate(tm, "GetSub", gtxId, chain));
            Chain c = Topology.chain(chain);
            log("STAGE %s %s", chain, evaluate(f.contract(c.channel(), TWOPC), "GetStage", gtxId));
        }
    }

    static void state(Fabric f) {
        for (Chain c : CHAINS) {
            Contract p = f.contract(c.channel(), TWOPC);
            switch (c.id()) {
                case "A" -> {
                    for (String conta : List.of("Alice", "Bob")) {
                        log("cadeia A  SALDO~%-5s = %-5s %s", conta, evaluate(p, "GetBalance", conta),
                                evaluate(p, "IsLocked", "SALDO~" + conta));
                    }
                }
                case "B" -> log("cadeia B  IMOVEL~10   = %s %s", evaluate(p, "GetImovel", "10"),
                        evaluate(p, "IsLocked", "IMOVEL~10"));
                default -> {
                }
            }
        }
    }

    private static String need(Map<String, String> opt, String k) {
        String v = opt.get(k);
        if (v == null) {
            throw new IllegalArgumentException("--" + k + " obrigatório");
        }
        return v;
    }
}
