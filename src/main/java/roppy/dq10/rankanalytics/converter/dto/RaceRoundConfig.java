package roppy.dq10.rankanalytics.converter.dto;

import lombok.Data;

import java.util.List;

// rankanalytics.js(RACE_CONFIG_MAP)から抽出した、レース+回ごとの設定。
// S3の{raceKey}/{round}/race_config.txtとしてキャッシュされるJSONと1対1に対応する
@Data
public class RaceRoundConfig {

    // このJSONのスキーマバージョン(1から始まるインクリメント値)。
    // RaceRoundConfigLoaderが保存時に付与し、読み込み時に現在のスキーマバージョンと比較する。
    // 保存するデータの種類(フィールド)を変更した際にバージョンを上げる
    private int schemaVersion;

    private String title;

    // ISO8601形式
    private String beginTime;

    // ISO8601形式
    private String endTime;

    // このレース+回が追跡している順位の上限(ボーダー前後20位ウィンドウのクランプに使用)
    private int rankBorder;

    // 0=累積スコア型(PREDICTION_TYPE_LINEAR)、1=ハイスコア型(PREDICTION_TYPE_RANGE)
    private int predictionType;

    // pointの生値を表示用に変換する係数・小数桁数・単位(rankanalytics.jsのnumberFormatterに対応)。
    // 例: フィッシングコンテストはscale=0.1/decimalPlaces=1/unit="cm"(生値13090→"1309.0cm")
    private double pointScale;

    private int pointDecimalPlaces;

    private String pointUnit;

    private List<BorderDefinition> borders;
}
