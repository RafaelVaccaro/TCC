package mc2pc.app;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import mc2pc.common.model.Json;
import org.hyperledger.fabric.client.Contract;
import org.hyperledger.fabric.client.GatewayException;
import org.hyperledger.fabric.protos.common.Block;
import org.hyperledger.fabric.protos.common.BlockMetadataIndex;
import org.hyperledger.fabric.protos.common.BlockchainInfo;
import org.hyperledger.fabric.protos.common.ChannelHeader;
import org.hyperledger.fabric.protos.common.Envelope;
import org.hyperledger.fabric.protos.common.HeaderType;
import org.hyperledger.fabric.protos.common.Payload;
import org.hyperledger.fabric.protos.common.SignatureHeader;
import org.hyperledger.fabric.protos.ledger.rwset.NsReadWriteSet;
import org.hyperledger.fabric.protos.ledger.rwset.TxReadWriteSet;
import org.hyperledger.fabric.protos.ledger.rwset.kvrwset.KVRWSet;
import org.hyperledger.fabric.protos.ledger.rwset.kvrwset.KVRead;
import org.hyperledger.fabric.protos.ledger.rwset.kvrwset.KVWrite;
import org.hyperledger.fabric.protos.msp.SerializedIdentity;
import org.hyperledger.fabric.protos.peer.ChaincodeAction;
import org.hyperledger.fabric.protos.peer.ChaincodeActionPayload;
import org.hyperledger.fabric.protos.peer.ChaincodeEvent;
import org.hyperledger.fabric.protos.peer.ChaincodeInvocationSpec;
import org.hyperledger.fabric.protos.peer.ChaincodeProposalPayload;
import org.hyperledger.fabric.protos.peer.Endorsement;
import org.hyperledger.fabric.protos.peer.ProposalResponsePayload;
import org.hyperledger.fabric.protos.peer.Transaction;
import org.hyperledger.fabric.protos.peer.TxValidationCode;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static mc2pc.app.Fabric.log;
import static mc2pc.app.Topology.CHAINS;
import static mc2pc.app.Topology.MASTER_CHANNEL;
import static mc2pc.app.Topology.ORGCOORD;

/**
 * Lê todos os blocos dos canais (via chaincode de sistema qscc) e gera uma página HTML
 * para explorar o ledger: visão por GTX (fluxo do 2PC entre cadeias) e visão por canal/bloco.
 */
public final class Explorer {
    private Explorer() {
    }

    /** Nomes dos parâmetros por função, para exibir os argumentos (e decodificar as provas). */
    static final Map<String, List<String>> PARAMS = Map.ofEntries(
            Map.entry("RegisterChain", List.of("chainId", "channel", "chaincode", "orgsJson", "policy")),
            Map.entry("ConfigureCoordinator", List.of("chainId", "masterChannel", "txManagerChaincode", "orgsJson", "policy")),
            Map.entry("Begin", List.of("intentJson", "sigsJson")),
            Map.entry("RecordVote", List.of("gtxId", "chainId", "prova")),
            Map.entry("Decide", List.of("gtxId")),
            Map.entry("Timeout", List.of("gtxId")),
            Map.entry("RecordCompletion", List.of("gtxId", "chainId", "prova")),
            Map.entry("Prepare", List.of("gtxId", "subproposta", "provaSub")),
            Map.entry("Commit", List.of("gtxId", "provaDecisao")),
            Map.entry("Abort", List.of("gtxId", "provaDecisao")),
            Map.entry("SetBalance", List.of("conta", "saldo")),
            Map.entry("CreateImovel", List.of("id", "dono")),
            Map.entry("Transfer", List.of("from", "to", "amount")));

    /**
     * explorer [arquivo.html]              gera um retrato estático do ledger
     * explorer --serve [--port 8080]       servidor local que acompanha os blocos novos ao vivo
     */
    public static void run(String[] args) throws Exception {
        List<String> a = List.of(args);
        if (a.contains("--serve")) {
            int i = a.indexOf("--port");
            serve(i >= 0 && i + 1 < a.size() ? Integer.parseInt(a.get(i + 1)) : 8080);
            return;
        }
        Path out = Path.of(!a.isEmpty() ? a.get(0)
                : Path.of(System.getenv().getOrDefault("MC2PC_ROOT", "."), "explorer.html").toString());
        try (Fabric f = Fabric.connect(ORGCOORD, "User1")) {
            Ledger ledger = new Ledger(f);
            ledger.refresh();
            Files.writeString(out, render(ledger.json(), false));
        }
        log("explorer gerado: %s", out.toAbsolutePath());
    }

    // ------------------------------------------------------------------ modo servidor

    static void serve(int port) throws Exception {
        try (Fabric f = Fabric.connect(ORGCOORD, "User1")) {
            Ledger ledger = new Ledger(f);
            ledger.refresh();

            HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
            http.createContext("/", ex -> {
                String path = ex.getRequestURI().getPath();
                if (path.equals("/data.json")) {
                    // 204 quando o cliente já tem a versão atual (?v=N)
                    String q = ex.getRequestURI().getQuery();
                    if (q != null && q.equals("v=" + ledger.version())) {
                        reply(ex, 204, "application/json", new byte[0]);
                    } else {
                        reply(ex, 200, "application/json; charset=utf-8", ledger.json().getBytes(StandardCharsets.UTF_8));
                    }
                } else if (path.equals("/")) {
                    reply(ex, 200, "text/html; charset=utf-8", render(ledger.json(), true).getBytes(StandardCharsets.UTF_8));
                } else {
                    reply(ex, 404, "text/plain", "404".getBytes(StandardCharsets.UTF_8));
                }
            });
            http.setExecutor(Executors.newFixedThreadPool(4));
            http.start();
            log("explorer ao vivo em http://localhost:%d  (Ctrl+C para parar)", port);

            // Consulta a altura dos canais e busca só os blocos novos.
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    int added = ledger.refresh();
                    if (added > 0) {
                        log("+%d bloco(s) · versão %d", added, ledger.version());
                    }
                } catch (Exception e) {
                    log("falha ao ler o ledger: %s", e.getMessage());
                }
                Thread.sleep(1000);
            }
        }
    }

    private static void reply(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, status == 204 ? -1 : body.length);
        if (status != 204) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
        ex.close();
    }

    static String render(String json, boolean live) throws IOException {
        String html;
        try (InputStream in = Explorer.class.getResourceAsStream("/explorer.html")) {
            html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        // "</" dentro do JSON fecharia a tag <script>
        return html.replace("/*__DATA__*/null", json.replace("</", "<\\/"))
                .replace("/*__LIVE__*/false", Boolean.toString(live));
    }

    /** Cópia decodificada dos blocos de todos os canais, atualizada incrementalmente. */
    static final class Ledger {
        private final Fabric fabric;
        private final Map<String, List<Object>> blocks = new LinkedHashMap<>();
        private volatile String json = "{}";
        private volatile long version;

        Ledger(Fabric fabric) {
            this.fabric = fabric;
            blocks.put(MASTER_CHANNEL, new ArrayList<>());
            CHAINS.forEach(c -> blocks.put(c.channel(), new ArrayList<>()));
        }

        /** Busca os blocos que ainda não temos; devolve quantos foram adicionados. */
        synchronized int refresh() throws InvalidProtocolBufferException {
            int added = 0;
            for (Map.Entry<String, List<Object>> e : blocks.entrySet()) {
                String ch = e.getKey();
                Contract qscc = fabric.contract(ch, "qscc");
                long height = BlockchainInfo.parseFrom(query(qscc, "GetChainInfo", ch)).getHeight();
                for (long n = e.getValue().size(); n < height; n++) {
                    e.getValue().add(block(Block.parseFrom(query(qscc, "GetBlockByNumber", ch, Long.toString(n)))));
                    added++;
                }
            }
            if (added > 0 || version == 0) {
                version++;
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("geradoEm", Instant.now().toString());
                data.put("versao", version);
                List<Object> chs = new ArrayList<>();
                blocks.forEach((ch, bs) -> chs.add(Map.of("nome", ch, "blocos", bs)));
                data.put("canais", chs);
                json = Json.to(data);
            }
            return added;
        }

        String json() {
            return json;
        }

        long version() {
            return version;
        }
    }

    private static byte[] query(Contract qscc, String fn, String... args) {
        try {
            return qscc.evaluateTransaction(fn, args);
        } catch (GatewayException e) {
            throw new IllegalStateException("qscc." + fn + ": " + Fabric.describe(e), e);
        }
    }

    // ------------------------------------------------------------------ decodificação

    static Map<String, Object> block(Block b) throws InvalidProtocolBufferException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("numero", b.getHeader().getNumber());
        m.put("hashDados", hex(b.getHeader().getDataHash()));
        m.put("hashAnterior", hex(b.getHeader().getPreviousHash()));
        ByteString filter = b.getMetadata().getMetadataCount() > BlockMetadataIndex.TRANSACTIONS_FILTER_VALUE
                ? b.getMetadata().getMetadata(BlockMetadataIndex.TRANSACTIONS_FILTER_VALUE) : ByteString.EMPTY;
        List<Object> txs = new ArrayList<>();
        for (int i = 0; i < b.getData().getDataCount(); i++) {
            Map<String, Object> tx = tx(Envelope.parseFrom(b.getData().getData(i)));
            tx.put("validacao", i < filter.size() ? validation(filter.byteAt(i)) : "?");
            txs.add(tx);
        }
        m.put("txs", txs);
        return m;
    }

    static Map<String, Object> tx(Envelope env) throws InvalidProtocolBufferException {
        Map<String, Object> m = new LinkedHashMap<>();
        Payload p = Payload.parseFrom(env.getPayload());
        ChannelHeader chdr = ChannelHeader.parseFrom(p.getHeader().getChannelHeader());
        SignatureHeader shdr = SignatureHeader.parseFrom(p.getHeader().getSignatureHeader());
        HeaderType type = HeaderType.forNumber(chdr.getType());
        m.put("txId", chdr.getTxId());
        m.put("tipo", type == null ? String.valueOf(chdr.getType()) : type.name());
        m.put("timestamp", Instant.ofEpochSecond(chdr.getTimestamp().getSeconds(), chdr.getTimestamp().getNanos()).toString());
        m.put("criador", identity(shdr.getCreator()));
        if (type != HeaderType.ENDORSER_TRANSACTION) {
            return m;
        }

        Transaction t = Transaction.parseFrom(p.getData());
        ChaincodeActionPayload cap = ChaincodeActionPayload.parseFrom(t.getActions(0).getPayload());
        ChaincodeInvocationSpec cis = ChaincodeInvocationSpec.parseFrom(
                ChaincodeProposalPayload.parseFrom(cap.getChaincodeProposalPayload()).getInput());
        List<ByteString> raw = cis.getChaincodeSpec().getInput().getArgsList();
        String fn = raw.isEmpty() ? "" : text(raw.get(0));
        m.put("chaincode", cis.getChaincodeSpec().getChaincodeId().getName());
        m.put("funcao", fn);
        List<String> names = PARAMS.getOrDefault(fn, List.of());
        List<Object> args = new ArrayList<>();
        for (int i = 1; i < raw.size(); i++) {
            Map<String, Object> a = new LinkedHashMap<>();
            String name = i - 1 < names.size() ? names.get(i - 1) : "arg" + i;
            a.put("nome", name);
            String v = text(raw.get(i));
            if (name.startsWith("prova")) {
                a.put("prova", proof(v));
                a.put("valor", "(" + v.length() + " caracteres base64)");
            } else {
                a.put("valor", clip(v, 4000));
            }
            args.add(a);
        }
        m.put("args", args);

        List<String> endorsers = new ArrayList<>();
        for (Endorsement e : cap.getAction().getEndorsementsList()) {
            endorsers.add(identity(e.getEndorser()));
        }
        m.put("endossantes", endorsers);

        ChaincodeAction ca = ChaincodeAction.parseFrom(
                ProposalResponsePayload.parseFrom(cap.getAction().getProposalResponsePayload()).getExtension());
        m.put("resposta", clip(text(ca.getResponse().getPayload()), 4000));
        if (!ca.getEvents().isEmpty()) {
            ChaincodeEvent ev = ChaincodeEvent.parseFrom(ca.getEvents());
            if (!ev.getEventName().isEmpty()) {
                m.put("evento", Map.of("nome", ev.getEventName(), "payload", text(ev.getPayload())));
            }
        }
        m.put("rwset", rwset(TxReadWriteSet.parseFrom(ca.getResults())));
        return m;
    }

    static List<Object> rwset(TxReadWriteSet rws) throws InvalidProtocolBufferException {
        List<Object> out = new ArrayList<>();
        for (NsReadWriteSet ns : rws.getNsRwsetList()) {
            KVRWSet kv = KVRWSet.parseFrom(ns.getRwset());
            if (kv.getReadsCount() == 0 && kv.getWritesCount() == 0) {
                continue;
            }
            List<Object> reads = new ArrayList<>();
            for (KVRead r : kv.getReadsList()) {
                reads.add(Map.of("chave", printable(r.getKey()),
                        "versao", r.hasVersion() ? r.getVersion().getBlockNum() + ":" + r.getVersion().getTxNum() : "inexistente"));
            }
            List<Object> writes = new ArrayList<>();
            for (KVWrite w : kv.getWritesList()) {
                writes.add(Map.of("chave", printable(w.getKey()), "delete", w.getIsDelete(),
                        "valor", w.getIsDelete() ? "" : clip(text(w.getValue()), 4000)));
            }
            out.add(Map.of("namespace", ns.getNamespace(), "leituras", reads, "escritas", writes));
        }
        return out;
    }

    /** Decodifica uma prova (envelope de consulta endossada) para exibição. */
    static Map<String, Object> proof(String b64) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            Envelope env = Envelope.parseFrom(Base64.getDecoder().decode(b64));
            Payload p = Payload.parseFrom(env.getPayload());
            ChannelHeader chdr = ChannelHeader.parseFrom(p.getHeader().getChannelHeader());
            ChaincodeActionPayload cap = ChaincodeActionPayload.parseFrom(Transaction.parseFrom(p.getData()).getActions(0).getPayload());
            ChaincodeInvocationSpec cis = ChaincodeInvocationSpec.parseFrom(
                    ChaincodeProposalPayload.parseFrom(cap.getChaincodeProposalPayload()).getInput());
            List<String> args = cis.getChaincodeSpec().getInput().getArgsList().stream().map(Explorer::text).toList();
            ChaincodeAction ca = ChaincodeAction.parseFrom(
                    ProposalResponsePayload.parseFrom(cap.getAction().getProposalResponsePayload()).getExtension());
            m.put("canal", chdr.getChannelId());
            m.put("chaincode", cis.getChaincodeSpec().getChaincodeId().getName());
            m.put("funcao", args.isEmpty() ? "" : args.get(0));
            m.put("args", args.size() > 1 ? args.subList(1, args.size()) : List.of());
            m.put("resultado", clip(text(ca.getResponse().getPayload()), 4000));
            List<String> endorsers = new ArrayList<>();
            for (Endorsement e : cap.getAction().getEndorsementsList()) {
                endorsers.add(identity(e.getEndorser()));
            }
            m.put("endossantes", endorsers);
        } catch (Exception e) {
            m.put("erro", "não decodificável: " + e.getMessage());
        }
        return m;
    }

    // ------------------------------------------------------------------ util

    /** "MSP / CN do certificado". */
    static String identity(ByteString serialized) {
        try {
            SerializedIdentity sid = SerializedIdentity.parseFrom(serialized);
            X509Certificate c = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(sid.getIdBytes().toByteArray()));
            String dn = c.getSubjectX500Principal().getName();
            String cn = dn.replaceAll(".*CN=([^,]+).*", "$1");
            return sid.getMspid() + " / " + cn;
        } catch (Exception e) {
            return "?";
        }
    }

    static String validation(byte code) {
        TxValidationCode c = TxValidationCode.forNumber(code & 0xff);
        return c == null ? String.valueOf(code & 0xff) : c.name();
    }

    /** UTF-8 legível ou um resumo de bytes binários. */
    static String text(ByteString b) {
        try {
            String s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b.toByteArray())).toString();
            if (s.chars().allMatch(ch -> ch >= 0x20 || ch == '\n' || ch == '\r' || ch == '\t')) {
                return s;
            }
        } catch (CharacterCodingException e) {
            // binário
        }
        return "<binário, " + b.size() + " bytes>";
    }

    static String printable(String s) {
        return s.replaceAll("[\\x00-\\x1f]", "·");
    }

    static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "… (" + s.length() + " caracteres)";
    }

    static String hex(ByteString b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b.toByteArray()) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }
}
