package mc2pc.common.model;

/** Operação de negócio de uma subproposta. */
public class Op {
    public String type;
    public String from;
    public String to;
    public Long amount;      // transferToken
    public String imovelId;  // transferImovel

    public static Op transferToken(String from, String to, long amount) {
        Op o = new Op();
        o.type = States.OP_TRANSFER_TOKEN;
        o.from = from;
        o.to = to;
        o.amount = amount;
        return o;
    }

    public static Op transferImovel(String imovelId, String from, String to) {
        Op o = new Op();
        o.type = States.OP_TRANSFER_IMOVEL;
        o.from = from;
        o.to = to;
        o.imovelId = imovelId;
        return o;
    }

    public void validate() {
        if (isBlank(from) || isBlank(to) || from.equals(to)) {
            throw new IllegalArgumentException("op " + type + ": from/to inválidos");
        }
        if (States.OP_TRANSFER_TOKEN.equals(type)) {
            if (amount == null || amount <= 0) {
                throw new IllegalArgumentException("op " + type + ": amount deve ser > 0");
            }
        } else if (States.OP_TRANSFER_IMOVEL.equals(type)) {
            if (isBlank(imovelId)) {
                throw new IllegalArgumentException("op " + type + ": imovelId obrigatório");
            }
        } else {
            throw new IllegalArgumentException("op desconhecida: " + type);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
