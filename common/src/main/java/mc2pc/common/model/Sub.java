package mc2pc.common.model;

import java.util.List;

/** Subproposta para uma cadeia (SUB~id~chain). Conteúdo da prova P1 (GetSub). */
public class Sub {
    public String gtxId;
    public String chainId;
    public List<Op> ops;
    public long prazo;
    public String intentHash;
    public String estado;
}
