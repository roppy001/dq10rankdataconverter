package roppy.dq10.rankanalytics.converter;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import roppy.dq10.rankanalytics.converter.dto.PromptPayload;
import roppy.dq10.rankanalytics.converter.dto.Race;
import roppy.dq10.rankanalytics.converter.dto.RaceRoundConfig;
import roppy.dq10.rankanalytics.converter.dto.Subrace;
import roppy.dq10.rankanalytics.converter.dto.RankSnapshot;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;

// Lambdaのハンドラ設定は roppy.dq10.rankanalytics.converter.RankConverterMain::handleRequest のまま、
// S3イベント起動({"Records":[...]}形式)と、テスト起動({"raceKey":"slimerace","round":5}形式)の両方を扱う。
// 入力の型をJacksonが自動判定できないため、一旦Map<String,Object>で受け取り、"Records"キーの有無で判定する
public class RankConverterMain implements RequestHandler<Map<String, Object>, Object> {
    private static final Map<String,RaceConfig> RACE_CONFIG_MAP;
    static {
        Map<String,RaceConfig> m = new HashMap<>();
        for(RaceConfig r : RaceConfig.values()){
            m.put(r.getKey(),r);
        }
        RACE_CONFIG_MAP = Collections.unmodifiableMap(m);
    }

    private static final ObjectMapper LAMBDA_INPUT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public Object handleRequest(Map<String, Object> input, Context context) {
        try {
            RaceConfig raceConfig;
            int round;

            if (input.containsKey("Records")) {
                S3Event s3Event = LAMBDA_INPUT_MAPPER.convertValue(input, S3Event.class);
                String key = s3Event.getRecords().get(0).getS3().getObject().getKey();
                String [] tokens = key.split("/",-1);
                raceConfig = resolveRaceConfig(tokens[0]);
                round = Integer.parseInt(tokens[1]);
            } else {
                RaceRoundInput raceRoundInput = LAMBDA_INPUT_MAPPER.convertValue(input, RaceRoundInput.class);
                raceConfig = resolveRaceConfig(raceRoundInput.getRaceKey());
                round = raceRoundInput.getRound();
            }

            execute(raceConfig, round);
        } catch (Exception e) {
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

        RaceConfig raceConfig = resolveRaceConfig(raceKey);

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
        main.execute(raceConfig, round);

    }

    private static Set<String> parseDisabledRaceKeys(String envValue) {
        if (envValue == null || envValue.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> keys = new HashSet<>();
        for (String token : envValue.split(",")) {
            keys.add(token.trim());
        }
        return keys;
    }

    private static RaceConfig resolveRaceConfig(String raceKey) throws InitializationException {
        RaceConfig raceConfig = RACE_CONFIG_MAP.get(raceKey);
        if (raceConfig == null) {
            System.out.println("race key list");
            for (RaceConfig r : RaceConfig.values()) {
                System.out.println(r.getKey());
            }
            throw new InitializationException("Unknown racekey:" + raceKey);
        }
        return raceConfig;
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

        // AI解説文を生成する(環境変数DISABLE_AI_SUMMARY_RACESで無効化されたレースはスキップ)
        if (!parseDisabledRaceKeys(System.getenv("DISABLE_AI_SUMMARY_RACES")).contains(raceConfig.getKey())) {
            // レース+回ごとの設定(ボーダー順位・開催期間等)はSubraceに依らず共通のため、ループの外で1回だけ取得する
            RaceRoundConfig raceRoundConfig = null;
            String raceRoundConfigError = null;
            try {
                raceRoundConfig = RaceRoundConfigLoader.getInstance().load(raceConfig, round);
            } catch (Exception e) {
                raceRoundConfigError = e.getMessage() != null ? e.getMessage() : e.toString();
            }

            PromptSnapshotSelector promptSnapshotSelector = PromptSnapshotSelector.getInstance();
            OpenAiClient openAiClient = OpenAiClient.getInstance();
            for (int i = 0; i < race.getSubraceList().size(); i++) {
                Subrace subrace = race.getSubraceList().get(i);
                if (raceRoundConfig == null) {
                    subrace.setAiSummaryError(raceRoundConfigError);
                    continue;
                }
                try {
                    PromptPayload payload = promptSnapshotSelector.select(subrace, raceConfig, raceRoundConfig, round, i);
                    subrace.setAiSummary(openAiClient.generateSummary(raceConfig, raceRoundConfig, payload));
                } catch (Exception e) {
                    subrace.setAiSummaryError(e.getMessage() != null ? e.getMessage() : e.toString());
                }
            }
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
