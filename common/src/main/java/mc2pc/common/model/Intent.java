package mc2pc.common.model;

import java.util.List;

/** Intenção global assinada pelas partes (Fase 0). */
public class Intent {
    public String gtxId;
    public long deadline; // epoch (s)
    public List<Leg> legs;
}
