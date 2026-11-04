package mc2pc.txmanager;

import com.google.gson.reflect.TypeToken;
import mc2pc.common.cc.Crypto;
import mc2pc.common.cc.Ledger;
import mc2pc.common.cc.Proof;
import mc2pc.common.model.Decision;
import mc2pc.common.model.Gtx;
import mc2pc.common.model.Intent;
import mc2pc.common.model.Json;
import mc2pc.common.model.Leg;
import mc2pc.common.model.Op;
import mc2pc.common.model.PartySig;
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

import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static mc2pc.common.model.States.DEC_ABORT;
import static mc2pc.common.model.States.DEC_COMMIT;
import static mc2pc.common.model.States.DEC_PENDING;
import static mc2pc.common.model.States.STAGE_ABORTED;
import static mc2pc.common.model.States.STAGE_COMMITTED;
import static mc2pc.common.model.States.STAGE_PREPARED;
import static mc2pc.common.model.States.STAGE_REFUSED;
import static mc2pc.common.model.States.SUB_ABORTED;
import static mc2pc.common.model.States.SUB_COMMITTED;
import static mc2pc.common.model.States.SUB_CRIADA;
import static mc2pc.common.model.States.SUB_PREPARED;
import static mc2pc.common.model.States.SUB_RECUSADA;

/**
 * Coordenador do 2PC, executado na masterchain.
 *
 * <pre>
 * REG~&lt;chain&gt;      cadastro da cadeia (canal, chaincode, CAs das orgs, política)
 * GTX~&lt;id&gt;         registro global (participantes, decisão, prazo)
 * SUB~&lt;id&gt;~&lt;chain&gt; subproposta por cadeia (ops, estado)
 * </pre>
 */
@Contract(name = "txmanager", info = @Info(title = "TxManager", description = "Coordenador 2PC (masterchain)", version = "1.0"))
@Default
public final class TxManager implements ContractInterface {

    /** Cadastro de uma cadeia participante. */
    public static class ChainReg {
        public String chainId;
        public Trust trust;
    }

    static String gtxKey(String id) {
        return "GTX~" + id;
    }

    static String subKey(String id, String chain) {
        return "SUB~" + id + "~" + chain;
    }

    static String regKey(String chain) {
        return "REG~" + chain;
    }

    // ---------------------------------------------------------------- administração

    /**
     * Cadastra uma cadeia participante e o necessário para validar provas dela.
     *
     * @param orgsJson [{"mspId":"OrgAMSP","rootCerts":["-----BEGIN CERTIFICATE-----..."]}, ...]
     */
    @Transaction(name = "RegisterChain", intent = Transaction.TYPE.SUBMIT)
    public String registerChain(Context ctx, String chainId, String channel, String chaincode, String orgsJson,
                                String policy) {
        Ledger.requireAdmin(ctx);
        if (chainId == null || chainId.isBlank()) {
            throw new ChaincodeException("chainId obrigatório");
        }
        ChainReg reg = new ChainReg();
        reg.chainId = chainId;
        reg.trust = new Trust();
        reg.trust.channel = channel;
        reg.trust.chaincode = chaincode;
        reg.trust.policy = policy;
        reg.trust.orgs = Json.from(orgsJson, new TypeToken<List<Trust.OrgTrust>>() { }.getType());
        Proof.validate(reg.trust);
        Ledger.put(ctx, regKey(chainId), reg);
        return Json.to(reg);
    }

    // ---------------------------------------------------------------- Fase 1: Begin

    /**
     * Valida a intenção assinada pelas partes, cria a GTX e uma SUB por cadeia; emite Begin.
     *
     * @param sigsJson {"Alice":{"cert":"&lt;PEM&gt;","sig":"&lt;base64 DER&gt;"}, ...} — assinaturas sobre o JSON exato de intentJson
     */
    @Transaction(name = "Begin", intent = Transaction.TYPE.SUBMIT)
    public String begin(Context ctx, String intentJson, String sigsJson) {
        Intent intent = parse(() -> Json.from(intentJson, Intent.class), "intenção");
        Map<String, PartySig> sigs = parse(
                () -> Json.from(sigsJson, new TypeToken<Map<String, PartySig>>() { }.getType()), "assinaturas");
        Instant now = Ledger.now(ctx);

        if (intent.gtxId == null || intent.gtxId.isBlank()) {
            throw new ChaincodeException("gtxId obrigatório");
        }
        if (Ledger.get(ctx, gtxKey(intent.gtxId), Gtx.class) != null) {
            throw new ChaincodeException("GTX " + intent.gtxId + " já existe");
        }
        if (intent.deadline <= now.getEpochSecond()) {
            throw new ChaincodeException("prazo já expirado");
        }
        if (intent.legs == null || intent.legs.isEmpty()) {
            throw new ChaincodeException("intenção sem pernas");
        }

        byte[] intentBytes = intentJson.getBytes(StandardCharsets.UTF_8);
        Gtx gtx = new Gtx();
        gtx.gtxId = intent.gtxId;
        gtx.decisao = DEC_PENDING;
        gtx.prazo = intent.deadline;
        gtx.intentHash = Ledger.sha256Hex(intentBytes);

        for (Leg leg : intent.legs) {
            if (gtx.participantes.contains(leg.chainId)) {
                throw new ChaincodeException("cadeia " + leg.chainId + " repetida");
            }
            ChainReg reg = Ledger.require(ctx, regKey(leg.chainId), ChainReg.class, "cadeia " + leg.chainId);
            if (leg.ops == null || leg.ops.isEmpty()) {
                throw new ChaincodeException("cadeia " + leg.chainId + " sem operações");
            }
            for (Op op : leg.ops) {
                parse(() -> {
                    op.validate();
                    return op;
                }, "cadeia " + leg.chainId);
                // Quem cede algo (from) precisa ter assinado a intenção com um certificado
                // emitido por uma org da cadeia onde está cedendo.
                verifyParty(sigs, op.from, reg, intentBytes, now);
            }
            gtx.participantes.add(leg.chainId);

            Sub sub = new Sub();
            sub.gtxId = intent.gtxId;
            sub.chainId = leg.chainId;
            sub.ops = leg.ops;
            sub.prazo = intent.deadline;
            sub.intentHash = gtx.intentHash;
            sub.estado = SUB_CRIADA;
            Ledger.put(ctx, subKey(intent.gtxId, leg.chainId), sub);
        }
        Ledger.put(ctx, gtxKey(gtx.gtxId), gtx);
        Ledger.emit(ctx, "Begin", event("gtxId", gtx.gtxId, "subs", gtx.participantes));
        return Json.to(gtx);
    }

    private static void verifyParty(Map<String, PartySig> sigs, String party, ChainReg reg, byte[] msg, Instant now) {
        PartySig ps = sigs.get(party);
        if (ps == null) {
            throw new ChaincodeException("falta assinatura de " + party);
        }
        X509Certificate cert = Crypto.verifyCert(ps.cert, reg.trust.orgs, now);
        try {
            Crypto.verifySignature(cert, msg, Base64.getDecoder().decode(ps.sig));
        } catch (IllegalArgumentException e) {
            throw new ChaincodeException("assinatura de " + party + " malformada");
        } catch (ChaincodeException e) {
            throw new ChaincodeException("assinatura de " + party + " inválida");
        }
    }

    // ---------------------------------------------------------------- Fase 3: votos

    /** Verifica a prova P2 (GetStage da cadeia) e marca a SUB como PREPARED ou RECUSADA; emite VoteRecorded. */
    @Transaction(name = "RecordVote", intent = Transaction.TYPE.SUBMIT)
    public String recordVote(Context ctx, String gtxId, String chainId, String prova) {
        Gtx gtx = Ledger.require(ctx, gtxKey(gtxId), Gtx.class, "GTX " + gtxId);
        Sub sub = Ledger.require(ctx, subKey(gtxId, chainId), Sub.class, "SUB " + gtxId + "/" + chainId);
        if (!SUB_CRIADA.equals(sub.estado)) {
            return Json.to(sub); // idempotente: voto já registrado
        }
        if (!DEC_PENDING.equals(gtx.decisao)) {
            throw new ChaincodeException("GTX " + gtxId + " já decidida (" + gtx.decisao + ")");
        }
        Stage st = verifiedStage(ctx, gtxId, chainId, prova);
        switch (st.estado) {
            case STAGE_PREPARED -> sub.estado = SUB_PREPARED;
            case STAGE_REFUSED, STAGE_ABORTED -> sub.estado = SUB_RECUSADA;
            default -> throw new ChaincodeException("cadeia " + chainId + " ainda não votou (estado " + st.estado + ")");
        }
        Ledger.put(ctx, subKey(gtxId, chainId), sub);
        Ledger.emit(ctx, "VoteRecorded", event("gtxId", gtxId, "chainId", chainId, "voto", sub.estado));
        return Json.to(sub);
    }

    // ---------------------------------------------------------------- Fase 4: decisão

    /** Lê todas as SUB: COMMIT se todas PREPARED, ABORT se alguma RECUSADA; emite Decided. */
    @Transaction(name = "Decide", intent = Transaction.TYPE.SUBMIT)
    public String decide(Context ctx, String gtxId) {
        Gtx gtx = Ledger.require(ctx, gtxKey(gtxId), Gtx.class, "GTX " + gtxId);
        if (!DEC_PENDING.equals(gtx.decisao)) {
            return Json.to(gtx); // idempotente
        }
        boolean allPrepared = true;
        boolean anyRefused = false;
        for (String chain : gtx.participantes) {
            Sub sub = Ledger.require(ctx, subKey(gtxId, chain), Sub.class, "SUB " + gtxId + "/" + chain);
            allPrepared &= SUB_PREPARED.equals(sub.estado);
            anyRefused |= SUB_RECUSADA.equals(sub.estado);
        }
        if (anyRefused) {
            gtx.decisao = DEC_ABORT;
        } else if (allPrepared) {
            gtx.decisao = DEC_COMMIT;
        } else {
            throw new ChaincodeException("votos pendentes para GTX " + gtxId);
        }
        return decided(ctx, gtx);
    }

    /** Grava ABORT se o prazo passou e a decisão ainda é PENDING; emite Decided. */
    @Transaction(name = "Timeout", intent = Transaction.TYPE.SUBMIT)
    public String timeout(Context ctx, String gtxId) {
        Gtx gtx = Ledger.require(ctx, gtxKey(gtxId), Gtx.class, "GTX " + gtxId);
        if (!DEC_PENDING.equals(gtx.decisao)) {
            throw new ChaincodeException("GTX " + gtxId + " já decidida (" + gtx.decisao + ")");
        }
        if (Ledger.now(ctx).getEpochSecond() <= gtx.prazo) {
            throw new ChaincodeException("prazo ainda não expirou");
        }
        gtx.decisao = DEC_ABORT;
        return decided(ctx, gtx);
    }

    private static String decided(Context ctx, Gtx gtx) {
        Ledger.put(ctx, gtxKey(gtx.gtxId), gtx);
        Ledger.emit(ctx, "Decided", event("gtxId", gtx.gtxId, "decisao", gtx.decisao));
        return Json.to(gtx);
    }

    // ---------------------------------------------------------------- Fase 6: conclusão

    /**
     * Verifica a prova P4 (GetStage = COMMITTED/ABORTED conforme a decisão) e marca a SUB como terminal.
     * Quando todas as SUB estão terminais, marca a GTX como concluída e emite Completed.
     */
    @Transaction(name = "RecordCompletion", intent = Transaction.TYPE.SUBMIT)
    public String recordCompletion(Context ctx, String gtxId, String chainId, String prova) {
        Gtx gtx = Ledger.require(ctx, gtxKey(gtxId), Gtx.class, "GTX " + gtxId);
        if (DEC_PENDING.equals(gtx.decisao)) {
            throw new ChaincodeException("GTX " + gtxId + " ainda não decidida");
        }
        Sub sub = Ledger.require(ctx, subKey(gtxId, chainId), Sub.class, "SUB " + gtxId + "/" + chainId);
        if (isTerminal(sub)) {
            return Json.to(gtx); // idempotente
        }
        Stage st = verifiedStage(ctx, gtxId, chainId, prova);
        String expected = DEC_COMMIT.equals(gtx.decisao) ? STAGE_COMMITTED : STAGE_ABORTED;
        if (!expected.equals(st.estado)) {
            throw new ChaincodeException("cadeia " + chainId + " em " + st.estado + ", esperado " + expected);
        }
        sub.estado = DEC_COMMIT.equals(gtx.decisao) ? SUB_COMMITTED : SUB_ABORTED;
        Ledger.put(ctx, subKey(gtxId, chainId), sub);

        boolean all = true;
        for (String chain : gtx.participantes) {
            if (!chain.equals(chainId)) {
                all &= isTerminal(Ledger.require(ctx, subKey(gtxId, chain), Sub.class, "SUB"));
            }
        }
        if (all) {
            gtx.concluida = true;
            Ledger.put(ctx, gtxKey(gtxId), gtx);
            Ledger.emit(ctx, "Completed", event("gtxId", gtxId, "decisao", gtx.decisao));
        } else {
            Ledger.emit(ctx, "CompletionRecorded", event("gtxId", gtxId, "chainId", chainId, "estado", sub.estado));
        }
        return Json.to(gtx);
    }

    private static boolean isTerminal(Sub sub) {
        return SUB_COMMITTED.equals(sub.estado) || SUB_ABORTED.equals(sub.estado);
    }

    /** Autentica uma prova de GetStage(gtxId) vinda da cadeia chainId. */
    private static Stage verifiedStage(Context ctx, String gtxId, String chainId, String prova) {
        ChainReg reg = Ledger.require(ctx, regKey(chainId), ChainReg.class, "cadeia " + chainId);
        Proof.Result r = Proof.verify(prova, reg.trust, Ledger.now(ctx)).expect("GetStage", gtxId);
        Stage st = parse(() -> Json.from(r.payloadString(), Stage.class), "prova");
        if (!gtxId.equals(st.gtxId) || !chainId.equals(st.chainId)) {
            throw new ChaincodeException("prova de STAGE não corresponde a " + gtxId + "/" + chainId);
        }
        return st;
    }

    // ---------------------------------------------------------------- consultas (geram provas)

    @Transaction(name = "GetGtx", intent = Transaction.TYPE.EVALUATE)
    public String getGtx(Context ctx, String gtxId) {
        return Json.to(Ledger.require(ctx, gtxKey(gtxId), Gtx.class, "GTX " + gtxId));
    }

    /** Prova P1: lida pelo Prepare nas cadeias. */
    @Transaction(name = "GetSub", intent = Transaction.TYPE.EVALUATE)
    public String getSub(Context ctx, String gtxId, String chainId) {
        return Json.to(Ledger.require(ctx, subKey(gtxId, chainId), Sub.class, "SUB " + gtxId + "/" + chainId));
    }

    /** Prova P3: lida pelo Commit/Abort nas cadeias. */
    @Transaction(name = "GetDecision", intent = Transaction.TYPE.EVALUATE)
    public String getDecision(Context ctx, String gtxId) {
        Gtx gtx = Ledger.require(ctx, gtxKey(gtxId), Gtx.class, "GTX " + gtxId);
        return Json.to(new Decision(gtxId, gtx.decisao));
    }

    @Transaction(name = "GetChain", intent = Transaction.TYPE.EVALUATE)
    public String getChain(Context ctx, String chainId) {
        return Json.to(Ledger.require(ctx, regKey(chainId), ChainReg.class, "cadeia " + chainId));
    }

    // ---------------------------------------------------------------- util

    private static Map<String, Object> event(Object... kv) {
        Map<String, Object> m = new TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static <T> T parse(java.util.function.Supplier<T> f, String what) {
        try {
            return f.get();
        } catch (IllegalArgumentException e) {
            throw new ChaincodeException(what + ": " + e.getMessage());
        }
    }
}
