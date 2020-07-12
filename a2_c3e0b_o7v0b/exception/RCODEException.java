package exception;

/**
 * Created by junze on 17-03-07.
 */
public class RCODEException extends Exception {

    // Response code from server
    private int RCODE;

    public int getRCODE() {
        return RCODE;
    }

    public void setRCODE(int RCODE) {
        this.RCODE = RCODE;
    }

    public RCODEException(int RCODE) {
        super();
        this.RCODE = RCODE;
    }
}
