package mc2pc.common.model;

import java.util.ArrayList;
import java.util.List;

/** Registro global na masterchain (GTX~id). */
public class Gtx {
    public String gtxId;
    public List<String> participantes = new ArrayList<>();
    public String decisao;
    public long prazo;
    public String intentHash;
    public boolean concluida;
}
