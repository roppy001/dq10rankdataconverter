package roppy.dq10.rankanalytics.converter;

public class IllegalDataException extends Exception {
    IllegalDataException(){
        super();
    }
    IllegalDataException(Throwable t){
        super(t);
    }
    IllegalDataException(String s){
        super(s);
    }
    IllegalDataException(String s, Throwable t){
        super(s,t);
    }
}
