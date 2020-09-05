package roppy.dq10.rankanalytics.converter;

public class S3Exception extends Exception {
    S3Exception(){
        super();
    }
    S3Exception(Throwable t){
        super(t);
    }
    S3Exception(String s){
        super(s);
    }
    S3Exception(String s, Throwable t){
        super(s,t);
    }
}
