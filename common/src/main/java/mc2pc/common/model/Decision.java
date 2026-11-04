package mc2pc.common.model;

/** Conteúdo da prova P3 (GetDecision). */
public class Decision {
    public String gtxId;
    public String decisao;

    public Decision() {
    }

    public Decision(String gtxId, String decisao) {
        this.gtxId = gtxId;
        this.decisao = decisao;
    }
}
