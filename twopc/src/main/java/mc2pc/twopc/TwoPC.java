package mc2pc.twopc;

import com.google.gson.reflect.TypeToken;
import mc2pc.common.cc.Ledger;
import mc2pc.common.cc.Proof;
import mc2pc.common.model.Decision;
import mc2pc.common.model.Json;
import mc2pc.common.model.Op;
import mc2pc.common.model.Stage;
import mc2pc.common.model.Sub;
import mc2pc.common.model.Trust;
import org.hyperledger.fabric.contract.Context;
import org.hyperledger.fabric.contract.ContractInterface;
import org.hyperledger.fabric.contract.annotation.Contract;
import org.hyperledger.fabric.contract.annotation.Default;
import org.hyperledger.fabric.contract.annotation.Info;
import org.hyperledger.fabric.contract.annotation.Transaction;
import org.hyperledger.fabric.shim.ChaincodeException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import static mc2pc.common.model.States.DEC_ABORT;
import static mc2pc.common.model.States.DEC_COMMIT;
import static mc2pc.common.model.States.OP_TRANSFER_IMOVEL;
import static mc2pc.common.model.States.OP_TRANSFER_TOKEN;
import static mc2pc.common.model.States.STAGE_ABORTED;
import static mc2pc.common.model.States.STAGE_COMMITTED;
import static mc2pc.common.model.States.STAGE_NONE;
import static mc2pc.common.model.States.STAGE_PREPARED;
import static mc2pc.common.model.States.STAGE_REFUSED;
import static mc2pc.common.model.States.SUB_CRIADA;

/**
 * Participante do 2PC, executado em cada cadeia (channela, channelb, ...).
 *
 * <pre>
 * COORD-CONFIG       id desta cadeia + confiança na masterchain (CAs, política)
 * STAGE~&lt;id&gt;       estado local do 2PC (escritas pendentes, travas)
 * LOCK~&lt;chave&gt;     trava de uma chave de negócio por uma GTX
 * SALDO~&lt;conta&gt;    saldo de tokens          IMOVEL~&lt;id&gt;  {"id","dono"}
 * </pre>
 */
@Contract(name = "twopc", info = @Info(title = "TwoPC", description = "Participante 2PC (cadeias)", version = "1.0"))
@Default
public final class TwoPC implements ContractInterface {

    static final String CONFIG_KEY = "COORD-CONFIG";

    /** Configuração do coordenador. */
    public static class CoordConfig {
        public String chainId;
        public Trust trust;
    }

    public static class Imovel {
        public String id;
        public String dono;
    }

    public static class Lock {
        public String gtxId;
    }

    /** Regra de negócio não satisfeita: vira voto NÃO (REFUSED), não erro. */
    static final class Refusal extends RuntimeException {
        Refusal(String msg) {
            super(msg);
        }
    }

    static String stageKey(String gtxId) {
        return "STAGE~" + gtxId;
    }

    static String lockKey(String key) {
        return "LOCK~" + key;
    }

    static String saldoKey(String conta) {
        return "SALDO~" + conta;
    }

    static String imovelKey(String id) {
        return "IMOVEL~" + id;
    }

    // ---------------------------------------------------------------- administração

    /**
     * Define quem pode assinar provas da masterchain (CAs das orgs + política) e o id desta cadeia.
     *
     * @param orgsJson [{"mspId":"OrgCoordMSP","rootCerts":["-----BEGIN CERTIFICATE-----..."]}, ...]
     */
    @Transaction(name = "ConfigureCoordinator", intent = Transaction.TYPE.SUBMIT)
    public String configureCoordinator(Context ctx, String chainId, String masterChannel, String txManagerChaincode,
                                       String orgsJson, String policy) {
        Ledger.requireAdmin(ctx);
        CoordConfig cfg = new CoordConfig();
        cfg.chainId = chainId;
        cfg.trust = new Trust();
        cfg.trust.channel = masterChannel;
        cfg.trust.chaincode = txManagerChaincode;
        cfg.trust.policy = policy;
        try {
            cfg.trust.orgs = Json.from(orgsJson, new TypeToken<List<Trust.OrgTrust>>() { }.getType());
        } catch (IllegalArgumentException e) {
            throw new ChaincodeException("orgs: " + e.getMessage());
        }
        Proof.validate(cfg.trust);
        Ledger.put(ctx, CONFIG_KEY, cfg);
        return Json.to(cfg);
    }

    // ---------------------------------------------------------------- Fase 2: Prepare

    /**
     * Verifica a prova P1 (GetSub na masterchain) e as regras de negócio; trava as chaves e guarda
     * as escritas em espera (PREPARED) ou registra o voto NÃO (REFUSED). Emite Prepared/Refused.
     */
    @Transaction(name = "Prepare", intent = Transaction.TYPE.SUBMIT)
    public String prepare(Context ctx, String gtxId, String subproposta, String provaSub) {
        CoordConfig cfg = config(ctx);
        Stage existing = Ledger.get(ctx, stageKey(gtxId), Stage.class);
        if (existing != null) {
            return Json.to(existing); // idempotente; inclui ABORTED gravado por um Abort que chegou antes
        }

        Proof.Result r = Proof.verify(provaSub, cfg.trust, Ledger.now(ctx)).expect("GetSub", gtxId, cfg.chainId);
        Sub sub = Json.from(r.payloadString(), Sub.class);
        if (!gtxId.equals(sub.gtxId) || !cfg.chainId.equals(sub.chainId) || !SUB_CRIADA.equals(sub.estado)) {
            throw new ChaincodeException("prova de SUB não corresponde a " + gtxId + "/" + cfg.chainId);
        }
        List<Op> ops;
        try {
            ops = Json.from(subproposta, new TypeToken<List<Op>>() { }.getType());
        } catch (IllegalArgumentException e) {
            throw new ChaincodeException("subproposta: " + e.getMessage());
        }
        if (!Json.to(ops).equals(Json.to(sub.ops))) {
            throw new ChaincodeException("subproposta difere da registrada na masterchain");
        }

        Stage st = new Stage(gtxId, cfg.chainId, STAGE_PREPARED);
        try {
            if (Ledger.now(ctx).getEpochSecond() > sub.prazo) {
                throw new Refusal("prazo expirado");
            }
            st.escritas = plan(ctx, ops);
            st.travas = new ArrayList<>(st.escritas.keySet());
            for (String key : st.travas) {
                Lock l = Ledger.get(ctx, lockKey(key), Lock.class);
                if (l != null) {
                    throw new Refusal("chave " + key + " travada pela GTX " + l.gtxId);
                }
            }
        } catch (Refusal e) {
            Stage refused = new Stage(gtxId, cfg.chainId, STAGE_REFUSED);
            refused.motivo = e.getMessage();
            Ledger.put(ctx, stageKey(gtxId), refused);
            Ledger.emit(ctx, "Refused", refused);
            return Json.to(refused);
        }
        Lock lock = new Lock();
        lock.gtxId = gtxId;
        for (String key : st.travas) {
            Ledger.put(ctx, lockKey(key), lock);
        }
        Ledger.put(ctx, stageKey(gtxId), st);
        Ledger.emit(ctx, "Prepared", new TreeMap<>(Map.of("gtxId", gtxId, "chainId", cfg.chainId)));
        return Json.to(st);
    }

    /** Aplica as regras de negócio sobre o estado atual e devolve as escritas resultantes (chave -> valor). */
    private static SortedMap<String, String> plan(Context ctx, List<Op> ops) {
        SortedMap<String, String> w = new TreeMap<>();
        for (Op op : ops) {
            try {
                op.validate();
            } catch (IllegalArgumentException e) {
                throw new Refusal(e.getMessage());
            }
            switch (op.type) {
                case OP_TRANSFER_TOKEN -> {
                    String kf = saldoKey(op.from);
                    String kt = saldoKey(op.to);
                    long from = balance(ctx, w, kf);
                    if (from < op.amount) {
                        throw new Refusal("saldo insuficiente de " + op.from + ": " + from + " < " + op.amount);
                    }
                    w.put(kf, Long.toString(from - op.amount));
                    w.put(kt, Long.toString(balance(ctx, w, kt) + op.amount));
                }
                case OP_TRANSFER_IMOVEL -> {
                    String k = imovelKey(op.imovelId);
                    Imovel im = w.containsKey(k) ? Json.from(w.get(k), Imovel.class) : Ledger.get(ctx, k, Imovel.class);
                    if (im == null) {
                        throw new Refusal("imóvel " + op.imovelId + " inexistente");
                    }
                    if (!op.from.equals(im.dono)) {
                        throw new Refusal("imóvel " + op.imovelId + " pertence a " + im.dono + ", não a " + op.from);
                    }
                    im.dono = op.to;
                    w.put(k, Json.to(im));
                }
                default -> throw new Refusal("op desconhecida " + op.type);
            }
        }
        return w;
    }

    private static long balance(Context ctx, Map<String, String> pending, String key) {
        String v = pending.containsKey(key) ? pending.get(key) : ctx.getStub().getStringState(key);
        return v == null || v.isEmpty() ? 0 : Long.parseLong(v);
    }

    // ---------------------------------------------------------------- Fase 5: Commit / Abort

    /** Verifica a prova P3 (decisão COMMIT), aplica as escritas e libera as travas; emite Committed. */
    @Transaction(name = "Commit", intent = Transaction.TYPE.SUBMIT)
    public String commit(Context ctx, String gtxId, String provaDecisao) {
        verifyDecision(ctx, gtxId, provaDecisao, DEC_COMMIT);
        Stage st = Ledger.require(ctx, stageKey(gtxId), Stage.class, "STAGE " + gtxId);
        if (STAGE_COMMITTED.equals(st.estado)) {
            return Json.to(st); // idempotente
        }
        if (!STAGE_PREPARED.equals(st.estado)) {
            throw new ChaincodeException("COMMIT recebido com STAGE em " + st.estado);
        }
        st.escritas.forEach((k, v) -> ctx.getStub().putStringState(k, v));
        st.travas.forEach(k -> ctx.getStub().delState(lockKey(k)));
        Stage done = new Stage(gtxId, st.chainId, STAGE_COMMITTED);
        Ledger.put(ctx, stageKey(gtxId), done);
        Ledger.emit(ctx, "Committed", new TreeMap<>(Map.of("gtxId", gtxId)));
        return Json.to(done);
    }

    /**
     * Verifica a prova P3 (decisão ABORT), descarta as escritas e libera as travas; emite Aborted.
     * Se não houve Prepare, grava ABORTED para recusar um Prepare que chegue atrasado.
     */
    @Transaction(name = "Abort", intent = Transaction.TYPE.SUBMIT)
    public String abort(Context ctx, String gtxId, String provaDecisao) {
        CoordConfig cfg = verifyDecision(ctx, gtxId, provaDecisao, DEC_ABORT);
        Stage st = Ledger.get(ctx, stageKey(gtxId), Stage.class);
        if (st != null && STAGE_ABORTED.equals(st.estado)) {
            return Json.to(st); // idempotente
        }
        if (st != null && STAGE_COMMITTED.equals(st.estado)) {
            throw new ChaincodeException("ABORT recebido com STAGE já COMMITTED");
        }
        if (st != null && st.travas != null) {
            st.travas.forEach(k -> ctx.getStub().delState(lockKey(k)));
        }
        Stage done = new Stage(gtxId, cfg.chainId, STAGE_ABORTED);
        Ledger.put(ctx, stageKey(gtxId), done);
        Ledger.emit(ctx, "Aborted", new TreeMap<>(Map.of("gtxId", gtxId)));
        return Json.to(done);
    }

    private static CoordConfig verifyDecision(Context ctx, String gtxId, String prova, String expected) {
        CoordConfig cfg = config(ctx);
        Proof.Result r = Proof.verify(prova, cfg.trust, Ledger.now(ctx)).expect("GetDecision", gtxId);
        Decision d = Json.from(r.payloadString(), Decision.class);
        if (!gtxId.equals(d.gtxId) || !expected.equals(d.decisao)) {
            throw new ChaincodeException("decisão provada para " + d.gtxId + " é " + d.decisao + ", esperado " + expected);
        }
        return cfg;
    }

    // ---------------------------------------------------------------- consultas (geram provas)

    /** Provas P2/P4: estado local do 2PC (NONE se não houver). */
    @Transaction(name = "GetStage", intent = Transaction.TYPE.EVALUATE)
    public String getStage(Context ctx, String gtxId) {
        Stage st = Ledger.get(ctx, stageKey(gtxId), Stage.class);
        return Json.to(st != null ? st : new Stage(gtxId, config(ctx).chainId, STAGE_NONE));
    }

    /** Consulta se uma chave está travada (usado também pelas operações comuns de negócio). */
    @Transaction(name = "IsLocked", intent = Transaction.TYPE.EVALUATE)
    public String isLocked(Context ctx, String key) {
        Lock l = Ledger.get(ctx, lockKey(key), Lock.class);
        Map<String, Object> m = new TreeMap<>();
        m.put("key", key);
        m.put("locked", l != null);
        if (l != null) {
            m.put("gtxId", l.gtxId);
        }
        return Json.to(m);
    }

    // ---------------------------------------------------------------- negócio local

    /** Define o saldo de uma conta (setup). */
    @Transaction(name = "SetBalance", intent = Transaction.TYPE.SUBMIT)
    public String setBalance(Context ctx, String conta, long saldo) {
        Ledger.requireAdmin(ctx);
        requireUnlocked(ctx, saldoKey(conta));
        ctx.getStub().putStringState(saldoKey(conta), Long.toString(saldo));
        return Long.toString(saldo);
    }

    /** Cria/atualiza um imóvel (setup). */
    @Transaction(name = "CreateImovel", intent = Transaction.TYPE.SUBMIT)
    public String createImovel(Context ctx, String id, String dono) {
        Ledger.requireAdmin(ctx);
        requireUnlocked(ctx, imovelKey(id));
        Imovel im = new Imovel();
        im.id = id;
        im.dono = dono;
        Ledger.put(ctx, imovelKey(id), im);
        return Json.to(im);
    }

    /**
     * Transferência local comum (fora do 2PC) — respeita as travas.
     * Protótipo: não autentica o titular da conta.
     */
    @Transaction(name = "Transfer", intent = Transaction.TYPE.SUBMIT)
    public String transfer(Context ctx, String from, String to, long amount) {
        requireUnlocked(ctx, saldoKey(from));
        requireUnlocked(ctx, saldoKey(to));
        Map<String, String> none = Map.of();
        long b = balance(ctx, none, saldoKey(from));
        if (amount <= 0 || b < amount) {
            throw new ChaincodeException("transferência inválida ou saldo insuficiente");
        }
        ctx.getStub().putStringState(saldoKey(from), Long.toString(b - amount));
        ctx.getStub().putStringState(saldoKey(to), Long.toString(balance(ctx, none, saldoKey(to)) + amount));
        return "OK";
    }

    @Transaction(name = "GetBalance", intent = Transaction.TYPE.EVALUATE)
    public String getBalance(Context ctx, String conta) {
        return Long.toString(balance(ctx, Map.of(), saldoKey(conta)));
    }

    @Transaction(name = "GetImovel", intent = Transaction.TYPE.EVALUATE)
    public String getImovel(Context ctx, String id) {
        return Json.to(Ledger.require(ctx, imovelKey(id), Imovel.class, "imóvel " + id));
    }

    private static void requireUnlocked(Context ctx, String key) {
        Lock l = Ledger.get(ctx, lockKey(key), Lock.class);
        if (l != null) {
            throw new ChaincodeException("chave " + key + " travada pela GTX " + l.gtxId);
        }
    }

    private static CoordConfig config(Context ctx) {
        return Ledger.require(ctx, CONFIG_KEY, CoordConfig.class, "configuração do coordenador (ConfigureCoordinator)");
    }
}
