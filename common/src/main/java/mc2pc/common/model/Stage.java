package mc2pc.common.model;

import java.util.List;
import java.util.SortedMap;

/** Estado local do 2PC numa cadeia (STAGE~id). Conteúdo das provas P2 e P4 (GetStage). */
public class Stage {
    public String gtxId;
    public String chainId;
    public String estado;
    public SortedMap<String, String> escritas; // chave -> novo valor, aplicado no Commit
    public List<String> travas;
    public String motivo;

    public Stage() {
    }

    public Stage(String gtxId, String chainId, String estado) {
        this.gtxId = gtxId;
        this.chainId = chainId;
        this.estado = estado;
    }
}
