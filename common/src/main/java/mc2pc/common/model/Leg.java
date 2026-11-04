package mc2pc.common.model;

import java.util.List;

/** Perna da intenção em uma cadeia. */
public class Leg {
    public String chainId;
    public List<Op> ops;

    public Leg() {
    }

    public Leg(String chainId, List<Op> ops) {
        this.chainId = chainId;
        this.ops = ops;
    }
}
