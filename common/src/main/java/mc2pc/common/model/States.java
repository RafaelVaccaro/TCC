package mc2pc.common.model;

/** Constantes de estado do protocolo. */
public final class States {
    private States() {
    }

    // Decisão global (GTX)
    public static final String DEC_PENDING = "PENDING";
    public static final String DEC_COMMIT = "COMMIT";
    public static final String DEC_ABORT = "ABORT";

    // SUB na masterchain
    public static final String SUB_CRIADA = "CRIADA";
    public static final String SUB_PREPARED = "PREPARED";
    public static final String SUB_RECUSADA = "RECUSADA";
    public static final String SUB_COMMITTED = "COMMITTED";
    public static final String SUB_ABORTED = "ABORTED";

    // STAGE nas cadeias participantes
    public static final String STAGE_NONE = "NONE";
    public static final String STAGE_PREPARED = "PREPARED";
    public static final String STAGE_REFUSED = "REFUSED";
    public static final String STAGE_COMMITTED = "COMMITTED";
    public static final String STAGE_ABORTED = "ABORTED";

    // Tipos de operação de negócio
    public static final String OP_TRANSFER_TOKEN = "transferToken";   // SALDO~<conta>
    public static final String OP_TRANSFER_IMOVEL = "transferImovel"; // IMOVEL~<id>
}
