package mc2pc.common.model;

/** Certificado (PEM) da parte e assinatura ECDSA (DER, base64) sobre SHA-256 do JSON da intenção. */
public class PartySig {
    public String cert;
    public String sig;

    public PartySig() {
    }

    public PartySig(String cert, String sig) {
        this.cert = cert;
        this.sig = sig;
    }
}
