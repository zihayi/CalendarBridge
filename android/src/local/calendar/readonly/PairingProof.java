package local.calendar.readonly;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

final class PairingProof {
    static String randomHex(int size){byte[] value=new byte[size];new SecureRandom().nextBytes(value);return hex(value);}
    static String hex(byte[] value){StringBuilder result=new StringBuilder();for(byte item:value)result.append(String.format(Locale.ROOT,"%02x",item&255));return result.toString();}
    static String digest(String value)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)));}
    static String verificationCode(String id,String fingerprint,String nonce,String secret)throws Exception{
        byte[] bytes=MessageDigest.getInstance("SHA-256").digest(("CalendarBridge-pair-v1\0"+id+"\0"+fingerprint+"\0"+nonce+"\0"+secret).getBytes(StandardCharsets.US_ASCII));
        long number=0;for(int i=0;i<4;i++)number=(number<<8)|(bytes[i]&255);
        return String.format(Locale.ROOT,"%06d",number%1000000);
    }
}
