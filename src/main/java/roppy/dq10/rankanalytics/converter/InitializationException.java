package roppy.dq10.rankanalytics.converter;

public class InitializationException extends Exception {
    InitializationException() {
        super();
    }
    InitializationException(Throwable t) {
        super(t);
    }
    InitializationException(String s) {
        super(s);
    }
    InitializationException(String s, Throwable t) {
        super(s,t);
    }
}
