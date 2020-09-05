package roppy.dq10.rankanalytics.converter;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.http.client.protocol.RequestAcceptEncoding;
import roppy.dq10.rankanalytics.converter.dto.Race;
import roppy.dq10.rankanalytics.converter.dto.Subrace;
import roppy.dq10.rankanalytics.converter.dto.RankItem;
import roppy.dq10.rankanalytics.converter.dto.RankSnapshot;

import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.GZIPOutputStream;

public class RankConverterMain implements RequestHandler<S3Event, Object> {
    private static final Map<String,RaceConfig> RACE_CONFIG_MAP;
    static {
        Map<String,RaceConfig> m = new HashMap<>();
        for(RaceConfig r : RaceConfig.values()){
            m.put(r.getKey(),r);
        }
        RACE_CONFIG_MAP = Collections.unmodifiableMap(m);
    }

    @Override
    public Object handleRequest(S3Event input, Context context) {
        try{
            String key = input.getRecords().get(0).getS3().getObject().getKey();
            String [] tokens = key.split("/",-1);
            execute(RACE_CONFIG_MAP.get(tokens[0]),Integer.parseInt(tokens[1]));

        }catch (Exception e) {
            throw new RuntimeException(e);
        }
        return new Object();
    }

    // RANK_RACEKEY
    // RANK_ROUND

    public static void main(String args[]) throws Exception {
        String raceKey;
        if (args.length == 0) {
            raceKey = System.getenv("RANK_RACEKEY");
        } else {
            raceKey = args[0];
        }

        if (!RACE_CONFIG_MAP.containsKey(raceKey)) {
            System.out.println("scraper key list");
            for (RaceConfig r : RaceConfig.values()) {
                System.out.println(r.getKey());
            }
            throw new InitializationException("Unknown racekey:" + raceKey);
        }

        int round;
        try {
            if (args.length <= 1) {
                round = Integer.parseInt(System.getenv("RANK_ROUND"));
            } else {
                round = Integer.parseInt(args[1]);
            }
        } catch (NumberFormatException nfe) {
            throw new InitializationException("Round should be positive integer", nfe);
        }

        RankConverterMain main = new RankConverterMain();
        main.execute(RACE_CONFIG_MAP.get(raceKey), round);

    }

    public void execute(RaceConfig raceConfig, int round) throws Exception {

        S3Downloader downloader = S3Downloader.getInstance();
        Race race = downloader.download(raceConfig.getKey(), round);

        NameIdentifier nameIdentifier = NameIdentifier.getInstance();
        for(int i = 0;i<race.getSubraceList().size();i++) {
            Subrace subrace = race.getSubraceList().get(i);
            nameIdentifier.identify(subrace, raceConfig.getSubraceNameIdentifierConfig()[i]);
        }

        // snapshotListを指定の個数に絞り込む
        for(int i = 0;i < race.getSubraceList().size();i++) {
            Subrace subrace = race.getSubraceList().get(i);
            List<RankSnapshot> snapshotList = subrace.getSnapshotList();
            subrace.setSnapshotList(snapshotList.subList(
                    Math.max(snapshotList.size() - raceConfig.getSnapshotLengthLimit(), 0),
                    snapshotList.size()));
        }


        // JSON形式のデータを作成後、GZIP圧縮
        ObjectMapper om = new ObjectMapper();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try(GZIPOutputStream gos = new GZIPOutputStream(baos)) {
            gos.write(om.writeValueAsString(race).getBytes("utf-8"));
        }
        baos.close();
        byte [] bytes = baos.toByteArray();

        // FC2へアップロード処理を行う
        String password = System.getenv("FC2_FTP_PASSWORD");
        String fileName = String.format("/json/%s%d.json.gz",raceConfig.getKey(),round);

        FTPClient ftpClient = new FTPClient();
        ftpClient.setRemoteVerificationEnabled(false);
        ftpClient.connect("yumedqx.web.fc2.com");
        if(!ftpClient.login("yumedqx",password)){
            throw new RuntimeException("login error");
        }
        if(!ftpClient.setFileType(FTP.BINARY_FILE_TYPE)){
            throw new RuntimeException("set file type error");
        }

        ftpClient.enterLocalPassiveMode();

        ftpClient.storeFile(fileName,new ByteArrayInputStream(bytes));

        ftpClient.disconnect();

    }

}
