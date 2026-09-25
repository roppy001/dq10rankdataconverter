package roppy.dq10.rankanalytics.converter;

import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import roppy.dq10.rankanalytics.converter.dto.RaceRoundConfig;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;

// レース+回ごとの設定(境界順位・開催期間等)をS3(race_config.txt)からキャッシュ読み込みし、
// キャッシュが無い場合はrankanalytics.js(develop→master→FC2の順にフォールバック)からOpenAIで抽出してキャッシュする
public class RaceRoundConfigLoader {

    // RaceRoundConfigが保存するデータの種類(フィールド構成)を変更した際にインクリメントする。
    // キャッシュされたrace_config.txtのschemaVersionがこの値と異なる場合は古いキャッシュとみなし再生成する
    private static final int CURRENT_SCHEMA_VERSION = 1;

    // 生データ用のroppyracedataバケットとは別のバケットに保存する。
    // 同一バケットに保存するとPutObjectがS3イベント経由でLambdaの再帰起動を招く恐れがあるため
    private static final String BUCKET_NAME = "roppyraceconfig";

    private static final String DEVELOP_URL =
            "https://raw.githubusercontent.com/roppy001/dq10rankanalytics/develop/js/rankanalytics.js";

    private static final String MASTER_URL =
            "https://raw.githubusercontent.com/roppy001/dq10rankanalytics/master/js/rankanalytics.js";

    private static final String FC2_URL =
            "https://yumedqx.web.fc2.com/js/rankanalytics.js";

    private static final RaceRoundConfigLoader INSTANCE = new RaceRoundConfigLoader();

    private RaceRoundConfigLoader() {
    }

    public static RaceRoundConfigLoader getInstance() {
        return INSTANCE;
    }

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    public RaceRoundConfig load(RaceConfig raceConfig, int round) throws Exception {
        String s3Key = String.format("%s/%d/race_config.txt", raceConfig.getKey(), round);

        S3Client s3Client = S3Client.builder()
                .region(Region.AP_NORTHEAST_1)
                .build();

        try (InputStream is = s3Client.getObject(GetObjectRequest.builder()
                .bucket(BUCKET_NAME)
                .key(s3Key)
                .build())) {
            RaceRoundConfig cached = objectMapper.readValue(is, RaceRoundConfig.class);
            if (cached.getSchemaVersion() == CURRENT_SCHEMA_VERSION) {
                return cached;
            }
            // schemaVersionが異なる(保存されているデータの種類が古い)ため、下記でrankanalytics.jsから再抽出しキャッシュを上書きする
        } catch (NoSuchKeyException e) {
            // キャッシュが無いので、この後rankanalytics.jsから抽出する
        } catch (SdkException e) {
            throw new S3Exception(e);
        }

        String entryKey = raceConfig.getKey() + round;
        String jsSource = fetchJsSource(entryKey);
        RaceRoundConfig raceRoundConfig = OpenAiClient.getInstance().extractRaceRoundConfig(jsSource, entryKey);
        raceRoundConfig.setSchemaVersion(CURRENT_SCHEMA_VERSION);

        try {
            String json = objectMapper.writeValueAsString(raceRoundConfig);
            s3Client.putObject(PutObjectRequest.builder()
                            .bucket(BUCKET_NAME)
                            .key(s3Key)
                            .build(),
                    RequestBody.fromString(json, StandardCharsets.UTF_8));
        } catch (SdkException e) {
            // キャッシュ保存に失敗しても解説文自体は生成できているため、処理は継続する
        }

        return raceRoundConfig;
    }

    private String fetchJsSource(String entryKey) throws IOException, InterruptedException {
        String developSource = fetchUrl(DEVELOP_URL);
        if (containsEntryKey(developSource, entryKey)) {
            return developSource;
        }
        String masterSource = fetchUrl(MASTER_URL);
        if (containsEntryKey(masterSource, entryKey)) {
            return masterSource;
        }
        String fc2Source = fetchUrl(FC2_URL);
        if (containsEntryKey(fc2Source, entryKey)) {
            return fc2Source;
        }
        throw new IllegalStateException(
                "rankanalytics.jsのRACE_CONFIG_MAPに" + entryKey + "のエントリが見つかりません(develop/master/FC2共に)");
    }

    private boolean containsEntryKey(String jsSource, String entryKey) {
        return Pattern.compile("\\b" + Pattern.quote(entryKey) + "\\s*:").matcher(jsSource).find();
    }

    private String fetchUrl(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to fetch " + url + ": HTTP " + response.statusCode());
        }
        return response.body();
    }
}
