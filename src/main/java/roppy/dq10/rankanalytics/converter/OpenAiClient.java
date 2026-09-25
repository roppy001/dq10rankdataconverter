package roppy.dq10.rankanalytics.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import roppy.dq10.rankanalytics.converter.dto.BorderDefinition;
import roppy.dq10.rankanalytics.converter.dto.BorderSummary;
import roppy.dq10.rankanalytics.converter.dto.PromptPayload;
import roppy.dq10.rankanalytics.converter.dto.PromptSnapshot;
import roppy.dq10.rankanalytics.converter.dto.RaceRoundConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// OpenAI APIを呼び出すクライアント。
// 1. PromptPayload + システムプロンプトからボーダーごとのAI解説文を生成する(generateSummary)
// 2. rankanalytics.jsのソースからレース+回の設定(RaceRoundConfig)を抽出する(extractRaceRoundConfig)
public class OpenAiClient {

    private static final String DEFAULT_MODEL = "gpt-5-mini";

    private static final String API_URL = "https://api.openai.com/v1/chat/completions";

    // gpt-5-mini等の推論系モデルは応答まで時間がかかる場合があるため、単純なHTTP呼び出しより長めに確保する
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(90);

    private static final OpenAiClient INSTANCE = new OpenAiClient();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    public static OpenAiClient getInstance() {
        return INSTANCE;
    }

    public List<BorderSummary> generateSummary(RaceConfig raceConfig, RaceRoundConfig raceRoundConfig,
                                                 PromptPayload payload) throws Exception {
        String systemPrompt = buildSummarySystemPrompt(raceConfig, raceRoundConfig, payload);
        String userMessage = objectMapper.writeValueAsString(payload);
        String content = callChatCompletion(systemPrompt, userMessage);

        JsonNode root = objectMapper.readTree(content);
        JsonNode bordersNode = root.path("borders");
        if (!bordersNode.isArray() || bordersNode.isEmpty()) {
            throw new RuntimeException("OpenAI response did not contain borders array: " + content);
        }

        Map<Integer, String> borderNameByRank = new LinkedHashMap<>();
        for (BorderDefinition border : raceRoundConfig.getBorders()) {
            borderNameByRank.put(border.getRank(), border.getBorderName());
        }

        List<BorderSummary> result = new ArrayList<>();
        for (JsonNode node : bordersNode) {
            int rank = node.path("border").asInt();
            String summaryText = node.path("summary").asText();
            if (summaryText.isEmpty()) {
                throw new RuntimeException("OpenAI response missing summary for border " + rank + ": " + content);
            }
            BorderSummary borderSummary = new BorderSummary();
            borderSummary.setBorder(rank);
            borderSummary.setBorderName(borderNameByRank.get(rank));
            borderSummary.setSummary(summaryText);
            result.add(borderSummary);
        }
        return result;
    }

    public RaceRoundConfig extractRaceRoundConfig(String jsSource, String entryKey) throws Exception {
        String systemPrompt = buildConfigExtractionSystemPrompt(entryKey);
        String content = callChatCompletion(systemPrompt, jsSource);

        JsonNode root = objectMapper.readTree(content);
        if (root.has("error")) {
            throw new IllegalStateException("rankanalytics.jsに" + entryKey + "のエントリが見つかりません: "
                    + root.path("error").asText());
        }

        RaceRoundConfig config = new RaceRoundConfig();
        config.setTitle(root.path("title").asText());
        config.setBeginTime(root.path("beginTime").asText());
        config.setEndTime(root.path("endTime").asText());
        config.setRankBorder(root.path("rankBorder").asInt());
        config.setPredictionType(root.path("predictionType").asInt());
        config.setPointScale(root.path("pointScale").asDouble());
        config.setPointDecimalPlaces(root.path("pointDecimalPlaces").asInt());
        config.setPointUnit(root.path("pointUnit").asText());

        List<BorderDefinition> borders = new ArrayList<>();
        for (JsonNode node : root.path("borders")) {
            BorderDefinition border = new BorderDefinition();
            border.setRank(node.path("rank").asInt());
            border.setBorderName(node.path("borderName").asText());
            borders.add(border);
        }
        config.setBorders(borders);

        if (borders.isEmpty() || config.getBeginTime().isEmpty() || config.getEndTime().isEmpty()
                || config.getPointUnit().isEmpty() || config.getPointScale() <= 0) {
            throw new IllegalStateException("rankanalytics.jsから" + entryKey + "の設定を正しく抽出できませんでした: " + content);
        }

        return config;
    }

    private String callChatCompletion(String systemPrompt, String userMessage) throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("OPENAI_API_KEY is not set");
        }
        String model = System.getenv("OPENAI_MODEL");
        if (model == null || model.isEmpty()) {
            model = DEFAULT_MODEL;
        }

        // gpt-5-mini等の推論系モデルはtemperatureにデフォルト値(1)以外を指定できないため付与しない
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userMessage)
                )
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("OpenAI API error: HTTP " + response.statusCode() + " " + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        return root.path("choices").path(0).path("message").path("content").asText();
    }

    private String buildSummarySystemPrompt(RaceConfig raceConfig, RaceRoundConfig raceRoundConfig, PromptPayload payload) {
        String bordersText = bordersToText(raceRoundConfig.getBorders());
        int snapshotCount = payload.getSnapshots().size();
        String latestTime = latestSnapshotTime(payload);
        boolean isFinal = isFinalResult(raceRoundConfig.getEndTime(), latestTime);

        return "あなたはドラゴンクエストXのランキングイベント「" + raceConfig.getRaceName() + "」の実況解説者です。\n" +
                "以下のJSON形式のランキングデータ(直近" + snapshotCount + "回の更新分、各ボーダー順位の前後20位を抽出したもの)を分析し、ボーダーごとに現在の状況を解説する文章を作成してください。\n" +
                buildPeriodInstructionText(raceRoundConfig, latestTime, isFinal) +
                "\n" +
                "## 入力データの形式\n" +
                "- snapshots: 時系列順(古い→新しい)の更新データの配列(1件が1回の順位更新に対応)\n" +
                "  - time: 更新日時(ISO8601)\n" +
                "  - rankList: その更新時点における順位データの配列(rank/point/name/id)\n" +
                "    - 抽出範囲は各ボーダー(" + bordersText + "位)の前後20位(このレースの追跡順位数でクランプ)を統合したものであり、全順位が含まれるわけではない\n" +
                "- pointは内部処理用の生値であり、画面表示用の値ではありません。本文中で得点に言及する際は、必ず「生値 × " + raceRoundConfig.getPointScale() + "」を計算し、小数点以下" + raceRoundConfig.getPointDecimalPlaces() + "桁に丸めた上で、末尾に単位「" + raceRoundConfig.getPointUnit() + "」を付けて表記してください(得点差分・増加量などpointから導出する数値も同様に変換すること)。例えば生値が13090であれば「" + formatSamplePoint(raceRoundConfig) + "」のように表記し、「13090" + raceRoundConfig.getPointUnit() + "」のように生値をそのまま数値として書いてはいけません\n" +
                "- 変換後の数値の桁が大きい場合は、桁数を読み取りやすくするため「10,000,000」のようなカンマ区切り表記、または「0.1億」のような万・億単位の表記のいずれかを用いてください。区切りの無い「10000000」のような表記は避けてください\n" +
                "- nameが「(ないしょ)」のプレイヤーは匿名希望者です。名前を明かさず「(ないしょ)」のまま、または「匿名の参加者」のように言及してください。idで同一人物として扱って構いませんが、実名は絶対に出力しないでください\n" +
                "- idはデータ上でプレイヤーを同一人物として追跡するための内部識別子です。分析の際に同一人物判定に使うのは構いませんが、出力する文章には「id=0」のようにid自体を書かないでください。プレイヤーに言及する際はnameのみ(匿名の場合は「(ないしょ)」または「匿名の参加者」)を使ってください\n" +
                "\n" +
                "## 分析観点(以下の4つの観点で分析すること)\n" +
                buildAnalysisAxesText(raceRoundConfig.getPredictionType(), bordersText, snapshotCount, isFinal) +
                "\n" +
                "## 出力形式\n" +
                "説明や前置きなしで、以下の1つのフィールドのみを持つJSONオブジェクトのみを出力してください。判定に使った根拠データや分析過程そのものは出力に含めないでください。\n" +
                "\n" +
                "{\"borders\": [{\"border\": <ボーダー順位の数値>, \"summary\": \"<そのボーダーの解説文>\"}, ...]}\n" +
                "\n" +
                "bordersの各要素は、以下の順位について順序通りに1つずつ作成してください: " + bordersText + "\n" +
                "各summaryは以下の条件に従ってください(" + (isFinal
                        ? "文体は解説風。このラウンドは終了しているため、推測表現は使わず確定した結果として断定的に書くこと"
                        : "文体は解説風、過度な断定は避ける") + "):\n" +
                "- 300字以内に収める\n" +
                "- idの値そのもの(例: id=0)は書かないこと\n" +
                "- 入力データの項目名(snapshots等)にかかわらず、文中では「スナップショット」という語を使わず、代わりに「更新」という語を使うこと";
    }

    // 最新の更新時刻がラウンドの終了時刻以上であれば、途中経過ではなく最終結果として扱う
    private boolean isFinalResult(String endTime, String latestTime) {
        if (endTime == null || endTime.isEmpty() || latestTime == null || latestTime.isEmpty()) {
            return false;
        }
        try {
            LocalDateTime end = LocalDateTime.parse(endTime);
            LocalDateTime latest = LocalDateTime.parse(latestTime);
            return !latest.isBefore(end);
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private String buildPeriodInstructionText(RaceRoundConfig raceRoundConfig, String latestTime, boolean isFinal) {
        if (isFinal) {
            return "このラウンドの開催期間は" + raceRoundConfig.getBeginTime() + "から" + raceRoundConfig.getEndTime() + "までで、既に終了しています。最新の更新時刻(" + latestTime + ")は終了時刻以降であるため、これは途中経過ではなく最終結果です。「〜と予想されます」「〜が見込まれます」のような進行中を前提にした推測表現は使わず、「〜という結果になりました」のように確定した事実として断定的に記述してください。\n";
        }
        return "このラウンドの開催期間は" + raceRoundConfig.getBeginTime() + "から" + raceRoundConfig.getEndTime() + "までです。最新の更新時刻(" + latestTime + ")が期間全体のどの時点に当たるかを踏まえて、終盤の着地予想を行ってください。\n";
    }

    // predictionType=0(累積スコア型)は得点推移中心、predictionType=1(ハイスコア型)は既存ランクイン者の得点変動が
    // まれで新規の高得点保持者が既存者を押し出す形で入れ替わるため、新規ランクイン人数中心の観点に文言を差し替える。
    // isFinalがtrueの場合、勢い・ペース分析の締めを「着地予想」ではなく「結果の振り返り」に差し替える
    private String buildAnalysisAxesText(int predictionType, String bordersText, int snapshotCount, boolean isFinal) {
        String landingPhrase = isFinal ? "この結果に至るまでの終盤の展開を振り返る" : "終盤の着地予想を行う";
        if (predictionType == 1) {
            return "1. 順位変動・攻防: 隣接する更新間でidの順位変化(オーバーテイク)を検出する。特に1位争いの得点差の推移に注目する\n" +
                    "2. ボーダーライン攻防: " + bordersText + "位それぞれについて、既存ランクイン者の得点はほとんど変動せず、新たに高得点を記録した参加者が既存の下位者を押し出す形で入れ替わる特性があるため、得点推移そのものよりも「前回の更新時点から今回の更新までの間に、そのボーダー以内に新たに何人がランクインしたか」という人数を中心に分析する。例:「前回更新時から10位以内に新たに2人がランクインしました」のように、直近1回分の更新間隔での人数の増減に注目すること(直近" + snapshotCount + "回全体の累計人数ではない)。得点の絶対値の変動は補足情報に留める\n" +
                    "3. 勢い・ペース分析: 得点増加率ではなく、更新ごとの新規ランクイン人数(前回更新からの増加人数)の推移から、そのボーダー圏の入れ替わりの勢い(激しさ)を判定し、" + landingPhrase + "\n" +
                    "4. 新規ランクイン/圏外落ち: ある更新の抽出範囲に存在したidが、次の更新の抽出範囲に存在しない場合、新規ランクインまたは圏外落ちの可能性がある。ただし抽出範囲外に出ただけの可能性もあるため断定は避け、「圏外に去った可能性があります」のように書く(このレースでは頻繁に発生しうる)\n";
        }
        return "1. 順位変動・攻防: 隣接する更新間でidの順位変化(オーバーテイク)を検出する。特に1位争いの得点差の推移に注目する\n" +
                "2. ボーダーライン攻防: " + bordersText + "位それぞれについて、得点の推移(直近" + snapshotCount + "回の更新でどれだけ伸びたか)・保持者(id)の入れ替わり回数・次点との得点差(僅差かどうか)を分析する\n" +
                "3. 勢い・ペース分析: 更新間のtimeの差とpointの差から、時間あたりの得点増加率を計算し、急上昇・失速・頭打ち(複数回の更新にわたり得点が変化していない場合)を判定し、" + landingPhrase + "\n" +
                "4. 新規ランクイン/圏外落ち: ある更新の抽出範囲に存在したidが、次の更新の抽出範囲に存在しない場合、新規ランクインまたは圏外落ちの可能性がある。ただし抽出範囲外に出ただけの可能性もあるため断定は避け、「圏外に去った可能性があります」のように書く\n";
    }

    private String buildConfigExtractionSystemPrompt(String entryKey) {
        return "以下はドラゴンクエストXのランキング分析ツール(rankanalytics.js)のソースコード全文です。\n" +
                "このコード中のRACE_CONFIG_MAPオブジェクトから、キーが「" + entryKey + "」であるエントリを探してください。\n" +
                "\n" +
                "見つけたエントリについて、以下の変換を行ってJSONオブジェクトとして出力してください。\n" +
                "- title: エントリのtitleフィールドの値(文字列)をそのまま使用する\n" +
                "- beginTime: エントリのbeginTimeフィールド(new Date(year, monthIndex, day, hour, minute)形式。monthIndexは0始まり(0が1月)であることに注意)をISO8601形式の文字列(例: 2024-01-10T12:00:00)に変換する\n" +
                "- endTime: 同様にendTimeフィールドをISO8601形式の文字列に変換する\n" +
                "- rankBorder: エントリのrankBorderフィールドの値(数値)をそのまま使用する\n" +
                "- predictionType: エントリのpredictionTypeフィールドの値をそのまま数値として使用する(PREDICTION_TYPE_LINEARは0、PREDICTION_TYPE_RANGEは1として出力すること)\n" +
                "- numberFormatterから、以下の3つの値を抽出する。numberFormatterフィールドは、得点(pointの生値)を画面表示用の文字列に変換する関数(またはNORMAL_FORMATTER_GENERATOR('<単位>')のように文字列引数付きで生成された関数)への参照です。参照先の関数定義をファイル内から探し、その処理内容から以下を読み取ってください\n" +
                "  - pointScale: 生値に掛け合わせている係数(数値)。例えば`x * 0.1`のような処理をしていれば0.1、係数を掛けていなければ1\n" +
                "  - pointDecimalPlaces: 表示時の小数点以下の桁数(数値)。`toFixed(1)`のような処理をしていれば1、整数のまま(`Math.floor`等)であれば0\n" +
                "  - pointUnit: 表示時に末尾へ付与する単位の文字列。例えば`NORMAL_FORMATTER_GENERATOR('P')`であれば\"P\"、`+ 'cm'`のように文字列を連結していれば\"cm\"\n" +
                "  (例: フィッシングコンテストのFISHING_FORMATTERは`(x * 0.1).toFixed(1) + 'cm'`なので、生値13090はpointScale=0.1・pointDecimalPlaces=1・pointUnit=\"cm\"により\"1309.0cm\"と表示される)\n" +
                "- borders: エントリのbordersフィールドは、ファイル内で別途varとして定義された配列(例: RACE_10_100_LINEAR)への参照になっています。参照先の配列定義を探し、その各要素のrankIndex(0始まりの順位インデックス)に1を加算した値をrank、borderNameフィールドの値をそのままborderNameとして、[{\"rank\": <数値>, \"borderName\": \"<文字列>\"}, ...] の配列に変換する(配列内の順序は参照先の定義順のまま)\n" +
                "\n" +
                "出力は説明や前置きなしで、以下の形式のJSONオブジェクトのみとしてください。\n" +
                "{\"title\": \"...\", \"beginTime\": \"...\", \"endTime\": \"...\", \"rankBorder\": <数値>, \"predictionType\": <0または1>, \"pointScale\": <数値>, \"pointDecimalPlaces\": <数値>, \"pointUnit\": \"...\", \"borders\": [{\"rank\": <数値>, \"borderName\": \"...\"}, ...]}\n" +
                "\n" +
                "該当するキーのエントリが見つからない場合は、title・borders等の値を推測で埋めず、{\"error\": \"entry not found\"}のみを出力してください。";
    }

    private String latestSnapshotTime(PromptPayload payload) {
        List<PromptSnapshot> snapshots = payload.getSnapshots();
        if (snapshots.isEmpty()) {
            return "";
        }
        return snapshots.get(snapshots.size() - 1).getTime();
    }

    // システムプロンプトに埋め込む得点表記の具体例(生値13090をraceRoundConfigの変換ルールで整形したもの)
    private String formatSamplePoint(RaceRoundConfig raceRoundConfig) {
        double value = 13090 * raceRoundConfig.getPointScale();
        return String.format("%." + raceRoundConfig.getPointDecimalPlaces() + "f", value) + raceRoundConfig.getPointUnit();
    }

    private String bordersToText(List<BorderDefinition> borders) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < borders.size(); i++) {
            if (i > 0) {
                sb.append("/");
            }
            sb.append(borders.get(i).getRank());
        }
        return sb.toString();
    }
}
