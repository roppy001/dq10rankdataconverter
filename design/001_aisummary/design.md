# AI解説文生成機能 要件定義

## 背景・目的

既存のランキングデータ変換パイプライン(S3 → NameIdentifier → JSON → GZIP → FTP)に、AIによる戦況解説文(自然文のコメンタリー)を追加する。

## 対象データ

`Race` → `Subrace`(複数) → `RankSnapshot`(時系列) → `RankItem`(rank / point / name / id)、および `Subrace` 単位の `DisplayName` リスト(id → 名前、匿名フラグ)。

レースは大きく累積スコア型(スライムレース、大富豪、バトエン)とハイスコア型(フィッシングコンテスト、カジノレイド)に分かれる。この分類はrankanalytics.jsの`RACE_CONFIG_MAP`の`predictionType`フィールドと一致する(`PREDICTION_TYPE_LINEAR`=0=累積スコア型、`PREDICTION_TYPE_RANGE`=1=ハイスコア型。2026-09-25追加。実データで確認済み: slimerace/daifugo/daifugom/pencilは全エントリが0、fishing/casinoraidは全エントリが1)。`predictionType`は`RaceRoundConfig`(下記「レース+回ごとの設定(race_config.txt)の取得」参照)の一部としてrace_config.txtに保存し、AI解説文生成時の分析観点の出し分けに使う(下記「分析観点のレース種別による出し分け」参照)。

## 確定した要件

- **出力粒度**: サブレースごとに、ボーダーごとの解説文の配列を生成する(2026-09-25改訂: 当初は1本の文字列だったが、クライアント側でボーダーごとに表形式表示したいという要件により配列に変更。詳細は下記「AI解説文の出力形式」を参照)
- **生成タイミング**: 既存パイプラインの実行都度(S3イベントごと)、全文を再生成する
- **出力先**: 既存の `Race`/`Subrace` の JSON に新フィールドを追加し、同じ `json.gz` に同梱してFTPアップロードする(解説専用の別ファイルは作らない)
- **文面の出し分け(2026-09-25改訂)**: 当初は最終結果の分析は行わず常に「途中経過」の文面のみを生成する方針だったが、最新の更新時刻がラウンドの終了時刻以上になった場合は「最終結果」としての文面に切り替える方針に変更した。判定方法・文面の違いは下記「途中経過/最終結果の出し分け」を参照
- **文体**: 解説風([summary.md](summary.md) のサンプル要約文の文体)に確定
- **分析に使用するAI**: OpenAI APIを使用する
- **ボーダー情報・開催期間の管理(2026-09-25改訂)**: 当初は[border.md](border.md)の内容を`RaceConfig` enumの`borderRanks`/`trackedRankLimit`フィールドに静的に転記する設計だったが、AI解析に開催期間(開始・終了日時)も含めたいという要件により、レース+回ごとに動的取得する方式に変更した。ボーダー順位・追跡順位数(rankBorder)・開催期間(beginTime/endTime)は、いずれも[rankanalytics.js](https://github.com/roppy001/dq10rankanalytics/blob/develop/js/rankanalytics.js)の`RACE_CONFIG_MAP`にレース+回単位(キー例: `slimerace7`)で定義されている。これをS3の`{raceKey}/{round}/race_config.txt`にJSON形式でキャッシュし(初回のみrankanalytics.jsから抽出、以降はキャッシュを読むだけ)、`RaceConfig` enumからは`borderRanks`/`trackedRankLimit`フィールドを削除した。詳細は下記「レース+回ごとの設定(race_config.txt)の取得」を参照
- **プロンプトに含めるデータ量**: 生のスナップショット全件(最大1000位分のランクリスト)をそのまま渡すことはしないが、Java側で4分析軸(ボーダー推移/順位変動/勢い/新規・圏外)を計算して渡す事前集計方式は、事前処理の基準設定が複雑になるため**採用しない(破棄)**。代わりに、直近5スナップショットについて、各スナップショットから「各ボーダーの前後20位(追跡順位数でクランプ)」を全て統合(重複除去)した範囲のみを抽出した、ほぼ生の順位データ(rank/point/name/id)をプロンプトに渡す。「1〜15位を必ず含める」という別枠の条件は不要で、いずれのレースも10位ボーダーを持つため、その前後20位ウィンドウ(最大30位まで)が1〜15位を自動的にカバーする。順位変動・勢い・新規ランクイン/圏外落ちといった分析自体はJavaで計算せず、OpenAI側に行わせる(下記「入力トークン量の見積もり」参照)
- **スナップショットが5件未満の場合(レース序盤)**: 集計・OpenAI呼び出しをスキップせず、その時点で存在するだけの件数で集計・生成する(5件は「最低限確保したい件数」であって「下回ったら生成しない」下限ではない)
- **障害時の挙動**: OpenAI呼び出しが失敗した場合、解説フィールド(`aiSummary`)は空にし、エラー原因フィールド(`aiSummaryError`、String型)には発生した例外の`getMessage()`(nullの場合は`toString()`)をそのまま格納する(種別分類や整形は行わない)。処理全体は失敗させず、既存の順位データのアップロードは通常通り継続する
- **レース単位でのAI要約無効化**: 環境変数 `DISABLE_AI_SUMMARY_RACES` にカンマ区切りで `RaceConfig` の `key`(例: `daifugo,daifugom`)を指定すると、該当レースはAI解説文生成をスキップする(コード変更・再デプロイなしで無効化できるようにするため環境変数で持つ。`FC2_FTP_PASSWORD`/`OPENAI_API_KEY` と同様の運用)。大富豪(`daifugo`/`daifugom`)は他レースと異なり常時開催でラウンドという区切りに乏しく、途中経過の解説文という設計と馴染まないため、初期値としてこの環境変数に設定して対象外とする想定。スキップした場合も解説フィールド・エラー原因フィールドは共に空のままとし、順位データのアップロードは通常通り継続する。`borderRanks` が空(トリニティ)の場合の対象外扱いとは別の、運用者が随時切り替えられる仕組みという位置付け

## レース+回ごとの設定(race_config.txt)の取得(2026-09-25追加)

### 背景

AI解析にレースの開催期間(いつ始まっていつ終わるか)も含めたいという要件が追加された。ボーダー順位・追跡順位数(rankBorder)・開催期間(beginTime/endTime)はいずれも[rankanalytics.js](https://github.com/roppy001/dq10rankanalytics/blob/develop/js/rankanalytics.js)の`RACE_CONFIG_MAP`にレース+回単位(例: `slimerace7`)で定義されており、レースの種類単位で固定の情報ではなく回ごとに異なる(特に開催期間は回ごとに違う)。そのため`RaceConfig` enumに静的に転記する方式ではなく、パイプライン実行時に動的取得する方式に変更した。

### rankanalytics.jsのRACE_CONFIG_MAPの構造

```javascript
var RACE_10_100_LINEAR = [
  { rankIndex : 0, borderName : '1位境界', predictionName : '1位予測' },
  { rankIndex : 9, borderName : '10位境界', predictionName : '10位予測' },
  { rankIndex : 99, borderName : '100位境界', predictionName : '100位予測' }
];
// (他にRACE_10_100_RANGE / RACE_10_100_200_RANGE / RACE_10_100_1000_LINEARが同様の形式で定義されている)

var RACE_CONFIG_MAP = {
  slimerace7 : {
    title : '第7回スライムレース',
    predictionType : PREDICTION_TYPE_LINEAR,
    numberFormatter : NORMAL_FORMATTER_GENERATOR('P'),
    beginTime : new Date(2024,0,10,12,0),
    endTime : new Date(2024,0,22,4,0),
    updateType : UPDATE_TYPE_EIGHT_HOURS,
    subraceNames : ['ランキング'],
    borders : RACE_10_100_LINEAR,
    rankBorder : 100
  },
  // ...
};
```

キーは`{raceKey}{round}`(`RaceConfig.getKey()` + 回番号、例: `slimerace7`・`daifugom9`・`casinoraid5`)。`borders`は値を直接持たず、`RACE_10_100_LINEAR`のようなファイル内の共通定数への参照になっている点に注意(`slimerace`と`casinoraid`は`rankBorder:100`の3段階ボーダー、`fishing`は200位を含む4段階、`pencil`/`daifugo`/`daifugom`は1000位を含む4段階。[border.md](border.md)の内容と一致する)。`trinity`はエントリ自体が存在しない(未使用レースのため)。

### 取得・キャッシュのフロー

`RaceRoundConfigLoader`(新規クラス、`S3Downloader`と同様のsingletonパターン)が以下の手順で`dto/RaceRoundConfig`を返す。

1. S3の`roppyraceconfig`バケット(2026-09-25改訂。下記「race_config.txt保存先バケットの分離」参照)、キー`{raceKey}/{round}/race_config.txt`を読みに行く。存在し、かつ`schemaVersion`が現在のスキーマバージョン(`RaceRoundConfigLoader.CURRENT_SCHEMA_VERSION`)と一致すれば、そのJSONをパースして返す(以降の手順は行わない)。`schemaVersion`が一致しない場合(古いスキーマでキャッシュされたもの)は、キャッシュが無い場合と同様に手順2以降へ進んで再生成し、キャッシュを上書きする(2026-09-25追加。「取得・キャッシュのバージョニング」参照)
2. 存在しない場合(`NoSuchKeyException`)、rankanalytics.jsを取得する。取得したテキストに対象キー(例: `slimerace7 :`)の文字列が含まれるかを単純な文字列検索で確認しながら、以下の順にフォールバックする。いずれにも含まれない場合はエラーとする(JS構文の正式なパースはせず、キーの存在確認のみJava側で行う)
   1. GitHubのdevelopブランチ(`https://raw.githubusercontent.com/roppy001/dq10rankanalytics/develop/js/rankanalytics.js`)
   2. GitHubのmasterブランチ(`https://raw.githubusercontent.com/roppy001/dq10rankanalytics/master/js/rankanalytics.js`)
   3. FC2に配置されている実運用版(`https://yumedqx.web.fc2.com/js/rankanalytics.js`。2026-09-25追加。develop/masterのいずれにも対象キーが無い場合の最終フォールバックとして参照する)
3. 取得できたrankanalytics.jsのソース全文を`OpenAiClient.extractRaceRoundConfig()`に渡し、OpenAIに変換させる。対象キーのエントリを探し、`borders`が参照している定数定義を解決し(`rankIndex + 1` = ボーダー順位、`borderName`はそのまま使用)、`beginTime`/`endTime`の`new Date(year, monthIndex, day, hour, minute)`(monthIndexは0始まり)をISO8601文字列に変換し、`predictionType`(数値、`PREDICTION_TYPE_LINEAR`=0/`PREDICTION_TYPE_RANGE`=1のいずれか。2026-09-25追加)をそのまま数値として転記し、`numberFormatter`が参照している関数定義を解決して`pointScale`/`pointDecimalPlaces`/`pointUnit`(2026-09-25追加。下記「得点の表示形式」参照)に変換した上で、以下のJSON形式で返させる

```json
{
  "schemaVersion": 1,
  "title": "第7回スライムレース",
  "beginTime": "2024-01-10T12:00:00",
  "endTime": "2024-01-22T04:00:00",
  "rankBorder": 100,
  "predictionType": 0,
  "pointScale": 1,
  "pointDecimalPlaces": 0,
  "pointUnit": "P",
  "borders": [
    {"rank": 1, "borderName": "1位境界"},
    {"rank": 10, "borderName": "10位境界"},
    {"rank": 100, "borderName": "100位境界"}
  ]
}
```

`predictionType`は0(累積スコア型)・1(ハイスコア型)のいずれかであり、AI解説文生成時の分析観点の出し分けに使う(下記「分析観点のレース種別による出し分け」参照)。`schemaVersion`はOpenAIには生成させず、`RaceRoundConfigLoader`がJava側で`CURRENT_SCHEMA_VERSION`の値を付与する(下記「取得・キャッシュのバージョニング」参照)。

4. 得られたJSONに`schemaVersion`(`CURRENT_SCHEMA_VERSION`の値)を設定した上でS3の`roppyraceconfig`バケットの`{raceKey}/{round}/race_config.txt`に保存する(次回以降はキャッシュを使うため、rankanalytics.jsの取得・OpenAI呼び出しはスキーマバージョンが変わらない限り初回のみ発生する)。開催期間・ボーダー定義は回が確定した後は変化しないため、キャッシュの有効期限・無効化の仕組みは設けない(スキーマバージョンの不一致のみがキャッシュ無効化の条件)
5. `RaceRoundConfig`を呼び出し元(`RankConverterMain`)に返す

### race_config.txt保存先バケットの分離(2026-09-25改訂)

当初は`race_config.txt`を既存の生データと同じ`roppyracedata`バケットの`{raceKey}/{round}/`prefix配下に保存していたが、この方式には別の問題があった。`roppyracedata`バケットはオブジェクト作成をトリガーにLambda(`RankConverterMain`)を起動するS3イベント通知が設定されており、`RaceRoundConfigLoader`がこのバケットへ`race_config.txt`を`PutObject`すると、そのPutObject自体がS3イベントとして扱われ、Lambdaの再帰的な起動を招く恐れがある(下記「race_config.txtとS3Downloaderのprefix衝突」で対応した一覧取得時の不具合とは別の問題)。

対応として、`race_config.txt`専用の新しいS3バケット`roppyraceconfig`を用意し、`RaceRoundConfigLoader`の保存先をそちらに変更した(`RaceRoundConfigLoader.BUCKET_NAME`)。キー形式(`{raceKey}/{round}/race_config.txt`)自体は変更していない。この変更に伴い、`roppyracedata`バケットに`race_config.txt`が置かれることは無くなったため、下記「race_config.txtとS3Downloaderのprefix衝突」で`S3Downloader.download()`に追加していたキー除外処理(`/race_config.txt`で終わるキーをスキップする処理)は不要になり削除した(`S3Downloader`は改めて本機能による変更対象外に戻った)。

運用面の前提として、`roppyraceconfig`バケットにはS3イベント通知を設定しないこと、また Lambda実行ロールに`roppyraceconfig`バケットへの`GetObject`/`PutObject`権限を追加する必要がある(インフラ側の対応が別途必要)。

### 取得・キャッシュのバージョニング(2026-09-25追加)

`RaceRoundConfig`は今後もフィールド追加(保存するデータの種類の変更)が想定される。追加のたびに運用者がS3上の`race_config.txt`を手動削除するのは、リリース前の今回限りの運用であり恒常的な運用には向かないため、キャッシュ自体にスキーマバージョンを持たせる方式に変更した。

- `RaceRoundConfig`に`schemaVersion`(int、1から始まるインクリメント値)フィールドを追加する
- `RaceRoundConfigLoader`は`CURRENT_SCHEMA_VERSION`(現状1)という定数を持ち、保存するデータの種類(フィールド構成)を変更するたびにこの値をインクリメントする(例えば今回の`pointScale`/`pointDecimalPlaces`/`pointUnit`追加のようなタイミング)
- S3から読み込んだキャッシュの`schemaVersion`が`CURRENT_SCHEMA_VERSION`と一致しない場合は、キャッシュが存在しない場合と同じ経路(rankanalytics.jsからの再取得・再生成)に進み、新しいスキーマバージョンでキャッシュを上書きする
- `schemaVersion`はOpenAIの抽出結果(`OpenAiClient.extractRaceRoundConfig()`の戻り値)には含めず、`RaceRoundConfigLoader.load()`側でS3保存直前に`CURRENT_SCHEMA_VERSION`を設定する(スキーマバージョンはコード側が管理する情報であり、rankanalytics.js由来のデータではないため)

`RaceRoundConfig`はレース+回単位の情報でありサブレースに依らず共通のため(casinoraidの4サブレースも共通のボーダー定義を使う。[border.md](border.md)の備考を参照)、`RankConverterMain.execute()`内ではサブレースのループの外側で1回だけ取得する。取得自体に失敗した場合(rankanalytics.jsにエントリが見つからない・S3/OpenAI呼び出しエラー等)は、既存の障害時設計方針に従い、その回の全サブレースの`aiSummaryError`にエラー内容を設定し、AI解説文生成をスキップして主パイプライン(順位データのアップロード)は継続する。`trinity`はrankanalytics.jsにエントリが存在しないため、この経路で自然に(専用の除外フラグを持たずに)AI解説文生成がスキップされエラーが記録される

### 得点の表示形式(2026-09-25追加・不具合対応)

実データでの動作確認時、フィッシングコンテストのAI解説文で、本来「1309.0cm」と表記すべき値が「13090点」と表記される不具合が見つかった。原因は、`point`フィールドの値がDQ10クライアント側の内部処理用の生値(固定小数点)であり、rankanalytics.js側で`numberFormatter`(フィッシングコンテストの場合は`FISHING_FORMATTER`、`(x * 0.1).toFixed(1) + 'cm'`)を通した表示用変換を行って初めて「1309.0cm」になる、という変換ルールをAI解説文生成時に一切考慮していなかったこと。他のレース(スライムレース等)も本来は`numberFormatter`(`NORMAL_FORMATTER_GENERATOR('P')`等)による単位付与を経ているが、これらは係数1の単純な単位付与のため生値をそのまま数値として使っても大きな見た目の破綻はなく、目立った不具合として顕在化していなかった。

対応として、`RaceRoundConfig`に`pointScale`(生値に掛け合わせる係数)・`pointDecimalPlaces`(表示時の小数点以下桁数)・`pointUnit`(末尾に付与する単位文字列)の3フィールドを追加し(上記JSON例参照)、`OpenAiClient.extractRaceRoundConfig()`のプロンプトで、`numberFormatter`が参照している関数定義(`NORMAL_FORMATTER_GENERATOR('<単位>')`または`FISHING_FORMATTER`のような個別定義)をOpenAIに解析させてこの3値を抽出するようにした(`borders`の参照解決と同様の手法)。フィッシングコンテストの場合は`pointScale=0.1`・`pointDecimalPlaces=1`・`pointUnit="cm"`となる。

`PromptPayload`に渡す`point`自体は生値のまま変更しない(順位変動・得点差分の計算をOpenAI自身に行わせる方針上、生値のまま扱えた方が計算しやすいため)。代わりに`OpenAiClient.generateSummary()`のシステムプロンプトに、「本文中で得点に言及する際は生値 × pointScaleを計算し、pointDecimalPlaces桁に丸めた上でpointUnitを付けて表記すること」という変換指示と具体例(生値13090の変換結果)を追加した。これにより、Java側で全RankItemのpointを事前に文字列変換する必要がなく(この方式は「プロンプトに含めるデータ量」で不採用とした事前集計方式と同様の理由で避けたい)、OpenAI側の1回の変換ルール適用だけで済む。

既に`race_config.txt`としてキャッシュ済みの回(`pointScale`等のフィールドを持たない、`schemaVersion`フィールド導入前のキャッシュ)は、`schemaVersion`を持たないため読み込み時に`0`として扱われ、`CURRENT_SCHEMA_VERSION`(1)と一致しないことから、次回アクセス時に自動的に再生成される(上記「取得・キャッシュのバージョニング」参照)。

### race_config.txtとS3Downloaderのprefix衝突(2026-09-25追加・不具合対応)

実データでの動作確認時、`S3Downloader.download()`が`IllegalDataException`で失敗する不具合が発生した。原因は、`race_config.txt`の保存先(`{raceKey}/{round}/race_config.txt`)が、既存の生データ(TSV形式のランキングスナップショット)と同じ`{raceKey}/{round}/`prefix配下だったこと。`S3Downloader.download()`はこのprefixに一致する全オブジェクトを`ListObjectsV2`で取得し、各オブジェクトの先頭行をタブ区切りとしてパースする実装になっているため、JSON1行のみの`race_config.txt`(タブを含まない)もリストに含まれてしまい、`firstTokens.length <= 1`の分岐で`IllegalDataException`を送出していた。

対応として、`S3Downloader.download()`のオブジェクト一覧取得部分で、キーが`/race_config.txt`で終わるものを除外するようにした。`race_config.txt`の保存場所自体(`{raceKey}/{round}/`配下)は変更していない。

**2026-09-25追記(このワークアラウンドの撤回)**: その後、`roppyracedata`バケットへの`PutObject`がLambdaの再帰起動を招く恐れがあるという別の問題が見つかり、`race_config.txt`の保存先を専用バケット`roppyraceconfig`に分離した(上記「race_config.txt保存先バケットの分離」参照)。これにより`race_config.txt`が`roppyracedata`バケットに存在すること自体が無くなったため、本項で追加した`S3Downloader.download()`のキー除外処理は不要になり削除した。`S3Downloader`は改めて本機能による変更対象外のクラスに戻った。

### JS→JSON変換をOpenAIで行う理由

`RACE_CONFIG_MAP`のエントリは`borders : RACE_10_100_LINEAR`のようにファイル内の別定数を参照する構造になっており、Java側でこれを正しく解決するには簡易的とはいえJSパーサ相当のロジックが必要になる。本機能はAI解説文生成自体もOpenAIに分析・判定を委ねる設計方針([Javaクラス構成](#javaクラス構成)を参照)であり、一貫性の観点から、rankanalytics.jsのソース全文をOpenAIに渡し、エントリの特定・参照解決・Dateの ISO8601変換までを1回のAPI呼び出しでまとめて行わせる。ファイル全体(約2,100行、数十KB程度)を渡してもGPT-5 miniの入力上限(272,000トークン)に対して十分小さく、かつS3キャッシュにより実際にこの変換が発生するのは各回で初回の1回のみのため、トークンコストは問題にならない

## 分析エンジン(OpenAI API)について

https://github.com/roppy001/aiinfobot (Discord向けAIニュース要約Bot)の実装を参考に、以下の方針を踏襲する。

### 踏襲するパターン

- **APIキー管理**: `OPENAI_API_KEY` を平文の環境変数として渡す。Secrets Manager/Parameter Storeは使用しない。本リポジトリは既に `FC2_FTP_PASSWORD` を同じ方式(環境変数)で扱っており、方針と一貫する
- **モデル指定**: 環境変数(例: `OPENAI_MODEL`)でモデル名を上書き可能にし、未設定時はコード内のデフォルト値として**GPT-5 mini**を使う(下記「入力トークン量の見積もり・モデル選定」参照)。ただし本プロジェクトはaiinfobotと異なり、順位変動・勢い・新規/圏外の**判定・計算自体をOpenAI側に行わせる**ため、単純な要約以上の推論能力が必要になる点に注意
- **プロンプト設計**: システムプロンプトに分析観点(本設計書の「提案した分析軸」に相当。順位変動・ボーダー攻防・勢い・新規/圏外の判定方法を明文化)と対象レースのボーダー順位を含め、ユーザーメッセージとして直近5スナップショット分の順位データ(各ボーダー前後20位を統合したもの)をJSONで渡す。出力は前置き無しの構造化JSON形式のみを返させ(`response_format: {"type": "json_object"}` 相当)。aiinfobotではtemperatureを低め(0.3)にしてブレを抑えていたが、本プロジェクトが採用するGPT-5 miniはtemperatureにデフォルト値(1)以外を指定するとHTTP 400になる推論系モデルのため、この方針は踏襲せずtemperatureパラメータ自体を付与しない
- **障害時の設計思想**: aiinfobotは収集元の一部失敗を許容し、部分的な結果でもレポート送信を続行する設計(処理全体を止めない)。本プロジェクトでも同様に、解説文生成(OpenAI呼び出し)が失敗しても既存の順位データのアップロードという主機能は継続させる。具体的には、解説フィールドを空にし、エラー原因を別フィールドに記載した上で処理を続行する(上記「確定した要件」参照)

### 本プロジェクト固有の技術的差異

- aiinfobotはPython(Lambda ランタイム3.13)で公式`openai`パッケージを使用しているが、本プロジェクトはJavaであり、`openai`パッケージは使えない。OpenAI REST APIを直接HTTP呼び出しする実装が必要
- JDK21・Gradle 8.10.2への移行は本機能の実装に先行して完了済み([PR #2](https://github.com/roppy001/dq10rankdataconverter/pull/2)でmasterにマージ済み、`sourceCompatibility`/`targetCompatibility`はJDK21)。そのためJava 11以降標準の`java.net.http.HttpClient`をそのまま使用できる(新規ライブラリの追加は不要)。旧JDK 8制約下でのApache HttpClient代替案は不要になった
- レスポンスのJSONパースは既存の`jackson-databind`がそのまま使える

## Javaクラス構成

前節の通り、順位変動・勢い・新規/圏外といった**分析(判定・計算)はJavaでは行わず、OpenAI側に行わせる**方針に変更した。そのためJava側の役割は「分析」ではなく「プロンプトに含めるデータの抽出(選別)」に縮小される。

- **選別と送信を分離する**: プロンプト用データを選ぶクラスと、OpenAI APIへ送信するクラスを分ける
  - 例: `PromptSnapshotSelector`(仮称) — `Subrace` + `RaceConfig` + `RaceRoundConfig`(ボーダー順位・追跡順位数を含む。2026-09-25改訂: 当初`RaceConfig.borderRanks`だったが、レース+回ごとに動的取得する`RaceRoundConfig`に変更。上記「レース+回ごとの設定(race_config.txt)の取得」を参照)を受け取り、直近5スナップショットそれぞれについて「各ボーダーの前後20位(追跡順位数でクランプ)」を統合(重複除去)した範囲に該当する`RankItem`のみを抽出したデータ(例: `PromptPayload`)を返す。順位変動・勢い等の計算は行わない、単純なフィルタリング処理。既存の`NameIdentifier`/`S3Downloader`と同様、パイプラインの1ステップとして`RankConverterMain.execute()`から呼び出す
  - 例: `OpenAiClient`(仮称) — `PromptPayload`とシステムプロンプト(分析観点 + 対象レースのボーダー順位 + 開催期間)を受け取り、`java.net.http.HttpClient`(JDKアップグレード後の標準API、上記「本プロジェクト固有の技術的差異」参照)でOpenAI APIを呼び出し、ボーダーごとの解説文の配列(または失敗時のエラー原因)を返す。あわせて、rankanalytics.jsのソースから`RaceRoundConfig`を抽出するメソッド(`extractRaceRoundConfig`)も持つ(上記「レース+回ごとの設定(race_config.txt)の取得」を参照)
- **選別結果はJSONでそのままプロンプトに渡す**: `PromptPayload`はJacksonの既存`ObjectMapper`でJSON化し、ユーザーメッセージとして渡す(手組みの日本語テキストにはしない)。実データから作成したサンプルJSONは [prompt-data-sample.md](prompt-data-sample.md) を参照
- 分析軸ごとにクラスを分割する設計は不要になった(分析自体がOpenAI側の責務のため)

### 改修対象・新規クラスのまとめ(クラス設計の出発点、2026-09-25改訂)

既存コード(`src/main/java/roppy/dq10/rankanalytics/converter/`)に対する改修範囲を以下の通り整理する。

**既存クラスの改修**

| クラス | 改修内容 |
|---|---|
| `RaceConfig`(enum) | システムプロンプトに埋め込む表示名`raceName`(「スライムレース」等、[border.md](border.md)のレース名列に対応)フィールドを追加する。ボーダー順位・追跡順位数は`RaceRoundConfig`(rankanalytics.jsから動的取得)に移管したため、`RaceConfig`には持たせない(`borderRanks`/`trackedRankLimit`フィールドは追加しない/削除した) |
| `dto/Subrace` | AI解説文フィールド`aiSummary`(`List<BorderSummary>`。ボーダーごとの解説文の配列。2026-09-25改訂: 当初は単一文字列だったが、クライアント側で表形式表示したいという要件により配列に変更)とエラー原因フィールド`aiSummaryError`(String)を追加する。出力粒度の要件(サブレースごとに1配列)により、`Race`や`RankSnapshot`ではなく`Subrace`に持たせる |
| `RankConverterMain` | `execute()`内、snapshotListを`snapshotLengthLimit`で絞り込んだ後・`ObjectMapper`でJSON化する前に、AI解説文生成ステップを追加する。`raceConfig.getKey()`が環境変数`DISABLE_AI_SUMMARY_RACES`(カンマ区切り)に含まれる場合はスキップする。まず`RaceRoundConfigLoader`でその回の`RaceRoundConfig`を1回だけ取得し(失敗時は全サブレースの`aiSummaryError`にエラーを設定してスキップ)、成功すればサブレースごとに`PromptSnapshotSelector`→`OpenAiClient`を呼び出す。OpenAI呼び出し失敗時はtry-catchで捕捉し、`aiSummary`を空・`aiSummaryError`にエラー内容を設定した上で処理を継続する |

**新規クラス**

| クラス | 役割 |
|---|---|
| `PromptSnapshotSelector` | `Subrace` + `RaceConfig` + `RaceRoundConfig`を受け取り、直近5スナップショットから各ボーダー前後20位を統合した`PromptPayload`を返す(単純なフィルタリング処理) |
| `dto/PromptPayload`(仮) | OpenAIへのユーザーメッセージとしてそのままJSON化するデータ構造。[prompt-data-sample.md](prompt-data-sample.md)のサンプルに対応 |
| `OpenAiClient` | `PromptPayload`とシステムプロンプトを受け取り、`java.net.http.HttpClient`でOpenAI APIを呼び出し、ボーダーごとの解説文の配列(`List<BorderSummary>`)またはエラー原因を返す(`generateSummary`)。システムプロンプトは`RaceRoundConfig.predictionType`の値により分析観点の文言を出し分ける(2026-09-25追加。下記「分析観点のレース種別による出し分け」参照)。あわせて、rankanalytics.jsのソース全文から`RaceRoundConfig`を抽出するメソッド(`extractRaceRoundConfig`)も持つ。`OPENAI_API_KEY`(必須)・`OPENAI_MODEL`(任意、デフォルト`gpt-5-mini`)を環境変数から読み取る |
| `RaceRoundConfigLoader` | S3の`roppyraceconfig`バケット(2026-09-25改訂。当初は`roppyracedata`バケットだったが、下記「race_config.txt保存先バケットの分離」の通り専用バケットに変更した)の`{raceKey}/{round}/race_config.txt`からレース+回ごとの設定(`RaceRoundConfig`)を読み込む。キャッシュが無ければrankanalytics.js(develop優先、フォールバックmaster)を取得し`OpenAiClient.extractRaceRoundConfig()`で変換した上でS3にキャッシュする。詳細は上記「レース+回ごとの設定(race_config.txt)の取得」を参照 |
| `dto/RaceRoundConfig` | レース+回ごとの設定。`schemaVersion`(キャッシュのスキーマバージョン。2026-09-25追加、下記「取得・キャッシュのバージョニング」参照)/`title`/`beginTime`/`endTime`(ISO8601)/`rankBorder`/`predictionType`(数値。0=累積スコア型/`PREDICTION_TYPE_LINEAR`、1=ハイスコア型/`PREDICTION_TYPE_RANGE`。2026-09-25追加)/`pointScale`/`pointDecimalPlaces`/`pointUnit`(得点の表示変換ルール。2026-09-25追加、下記「得点の表示形式」参照)/`borders`(`List<BorderDefinition>`)を持つ。S3の`race_config.txt`とJSONで1対1に対応する |
| `dto/BorderDefinition` | ボーダー定義。`rank`(順位数値)/`borderName`(rankanalytics.js由来の表示名、例:「1位境界」)を持つ |
| `dto/BorderSummary` | ボーダーごとのAI解説文。`border`(順位数値)/`borderName`/`summary`(解説文字列)を持つ。`Subrace.aiSummary`はこれの配列 |

**変更しない既存クラス**

`NameIdentifier` / `NameIdentifierConfig` / `S3Downloader` / `dto/Race` / `dto/RankSnapshot` / `dto/RankItem` / `dto/DisplayName` は本機能による変更対象外(順位識別・ダウンロード処理はAI解説文生成と独立しているため)。`S3Downloader`は一時的に改修対象に含まれたことがあったが(下記「race_config.txtとS3Downloaderのprefix衝突」参照)、`race_config.txt`の保存先バケット分離により変更対象外に戻った(下記「race_config.txt保存先バケットの分離」参照)

## 入力トークン量の見積もり・モデル選定

### 見積もり方法

`sample/slimerace7.json` の実データ(1件あたり平均52.4文字のコンパクトJSON、例: `{"rank":100,"point":74710000,"name":"（ないしょ）","id":200}`)を基準に、3つの設計案を比較した。トークン数は`tiktoken`の`o200k_base`エンコーディングで実測した値(GPT-5系モデルの近似値)。

| 設計案 | 内容 | 5スナップショット分の推定データ量 | 概算トークン数 |
|---|---|---|---|
| A. 生データをそのまま渡す(不採用) | 1000位分のランクリスト全件 × 5スナップショット = 5,000件 | 約262,200文字 | 約87,000〜131,000トークン |
| B. Java側で4分析軸を事前集計する(不採用・破棄) | ボーダー得点推移 + 順位変動・勢い・新規/圏外の集計結果 | 数千文字程度 | 概ね1,000〜2,000トークン |
| C. 各ボーダー前後20位を統合して渡す(採用) | slimerace/casinoraidなど(ボーダー1位/10位/100位): 51件/スナップショット。fishing/daifugo/daifugom/pencil(ボーダーが100位・200位・1000位を含む): 92件/スナップショット | 約13,700文字(slimerace実測) 〜 約25,100文字(fishing/daifugo系、推定) | 約5,400〜10,000トークン |

(設計案Cの内訳: 1位ボーダーの前後20位ウィンドウ(最大21位まで)と10位ボーダーの前後20位ウィンドウ(最大30位まで)を統合すると1〜30位で30件。「1〜15位を必ず含める」という別枠の条件は不要で、10位ボーダーのウィンドウが自動的にこれをカバーする。これに100位ボーダー前後20位(41件、slimerace/casinoraidは追跡順位数100でクランプされ21件)、fishing/daifugo系はさらに200位/1000位ボーダー前後20位(追跡順位数の上限でクランプされ実際は21件程度)を加える)

### 結論

- 設計案B(Java側で4分析軸を事前集計する方式)は、事前処理の基準(オーバーテイクの判定範囲、勢いの計算対象など)の設計・実装コストが大きいため、**破棄**した
- 採用した設計案Cは、各ボーダー前後20位のウィンドウを正しく統合した後も、追跡順位数が最大(1000位)のレースであっても、プロンプトに含めるデータ量は概算で10,000トークン程度に収まる
- **モデル選定: GPT-5 miniに確定**。2026年9月時点のOpenAI公式ドキュメントで確認した実際の仕様は、コンテキストウィンドウ400,000トークン・最大入力272,000トークン・最大出力128,000トークン。設計案Cの見積り(最大約10,000トークン、システムプロンプトを加えても1万数千トークン程度)は入力上限の4%程度に収まり、**追跡順位数・レース種別(累積スコア型/ハイスコア型)を問わずトークンサイズは全く問題にならない**
- ただし懸念点はトークン上限ではなく**推論精度**である。設計案Cでは順位変動・勢い・新規ランクイン/圏外落ちの**判定・計算をOpenAI自身が行う**必要があり、設計案B(Java側で計算済みの結果を渡すだけ)と比べて要求される推論の負荷が上がる。具体的には、最大92件×5スナップショット=460件程度の構造化データから、得点差分の計算、順位変動の検出、直近スナップショットに存在しないIDの検出(新規/圏外)を自力で行う必要があり、軽量モデルほど桁数の大きい得点(例: 600,000,000点)の差分計算や、複数IDにまたがる入れ替わり検出でのケアレスミスが起きやすい
- **結論**: トークンサイズはGPT-5 miniで十分に対応可能なため、コスト重視でGPT-5 miniを採用する。上記の推論精度への懸念は残るため、実装時に実データ(得点差分・順位変動・新規/圏外の検出漏れがないか)で検証し、精度が不十分な場合は上位モデルへの切り替えを検討する(`OPENAI_MODEL`環境変数で切り替え可能な設計のため、後から変更しやすい)

## 提案した分析軸

1. **順位変動・攻防** — 直近スナップショット間のオーバーテイク検出、首位争いの点差推移
2. **ボーダーライン攻防** — レースごとに複数存在するボーダー([border.md](border.md) 参照。例: 1位/10位/100位)それぞれについて、得点推移・僅差争い・保持者の入れ替わり頻度を分析する
3. **勢い・ペース分析** — 単位時間あたりの得点増加率(時給換算)による急上昇/失速判定、終盤の着地予想
4. **新規ランクイン/圏外落ち** — 新規表示・消失したプレイヤーの検出、匿名(（ないしょ）)プレイヤーは名前を伏せたまま動向のみ言及

以下は検討したが不要と判断し、対象外とした:
- ~~記録・マイルストーン(そのラウンドの最高得点更新、得点上限到達などの節目)~~
- ~~レース種目特性に応じた切り口(カジノレイド4種目やトリニティ6サブレースの横断比較など)~~

## 分析観点のレース種別による出し分け(2026-09-25追加)

### 背景

上記の4分析軸は、既存ランクイン者の得点が更新ごとに少しずつ積み上がっていく**累積スコア型**(スライムレース・大富豪・バトエン。`predictionType`=0)を前提にした観点になっている。一方**ハイスコア型**(フィッシングコンテスト・カジノレイド。`predictionType`=1)は性質が異なり、既にランクインしている参加者の得点が後から更新されることはまれで、新たに(そのボーダー圏内に入る)ハイスコアを記録した参加者が、既存の下位ランク者を押し出す形で順位が動く。そのため得点の推移そのものよりも、「そのボーダー周辺に新たに何人がランクインしたか(=何人が押し出されたか)」という**入れ替わり人数**を中心に分析させる方が実態に即した解説文になる。

### 分析観点の出し分け

`OpenAiClient`は`RaceRoundConfig.predictionType`の値に応じて、システムプロンプトの「分析観点」を以下のいずれかに出し分ける(`PromptPayload`が運ぶデータ自体は変わらず、rank/point/name/idのままでよい。人数のカウントも含めてOpenAI側の分析に委ねる方針は従来通り)。

**累積スコア型(predictionType=0)** — 従来通り、上記4分析軸をそのまま使う

**ハイスコア型(predictionType=1)** — 以下のように文言を変更する(2026-09-25改訂: 当初「直近の更新間」という曖昧な粒度だったが、「前回更新時から10位以内に新たに2人がランクインしました」のように、直近1回分の更新間隔(前回→今回)での人数変化に絞って分析させる方針に修正した。スナップショット全体(直近5回)を通じた累計人数ではない点に注意)
1. 順位変動・攻防: (変更なし)隣接する更新間でidの順位変化(オーバーテイク)を検出する。特に1位争いの得点差の推移に注目する
2. ボーダーライン攻防: 既存ランクイン者の得点はほとんど変動せず、新たに高得点を記録した参加者が既存の下位者を押し出す形で入れ替わる特性があるため、得点推移そのものよりも「前回の更新時点から今回の更新までの間に、そのボーダー以内に新たに何人がランクインしたか」という人数を中心に分析する(例:「前回更新時から10位以内に新たに2人がランクインしました」)。直近1回分の更新間隔での人数の増減が対象であり、直近5回全体の累計人数ではない。得点の絶対値の変動は補足情報に留める
3. 勢い・ペース分析: 得点増加率ではなく、更新ごとの新規ランクイン人数(前回更新からの増加人数)の推移から、そのボーダー圏の入れ替わりの勢い(激しさ)を判定し、終盤の着地予想を行う(最終結果の場合は下記「途中経過/最終結果の出し分け」の通り結果の振り返りに置き換える)
4. 新規ランクイン/圏外落ち: (変更なし、ただし累積スコア型よりも高頻度に発生する前提で言及する)ある更新の抽出範囲に存在したidが、次の更新の抽出範囲に存在しない場合、新規ランクインまたは圏外落ちの可能性がある。ただし抽出範囲外に出ただけの可能性もあるため断定は避け、「圏外に去った可能性があります」のように書く

## 途中経過/最終結果の出し分け(2026-09-25追加)

### 背景

当初は「本パイプラインでは最終結果の分析は行わず常に途中経過の文面のみを生成し、レース終了後の最終結果表示はクライアント側が別途対応する」という方針だったが、パイプライン自体がラウンドの開催期間(`RaceRoundConfig.beginTime`/`endTime`)を保持するようになったため、AI解説文自身にも「途中経過」か「最終結果」かを判定させて文体を切り替える方針に変更した。

### 判定方法

`OpenAiClient`が、直近の更新(`PromptPayload`の`snapshots`の最後の要素の`time`)と`RaceRoundConfig.endTime`をJavaの`LocalDateTime`として比較し、最新の更新時刻が終了時刻**と同じ、またはそれ以降**であれば「最終結果」、それ以外は「途中経過」と判定する(いずれもISO8601のローカル日時文字列同士の比較で、タイムゾーンは扱わない)。日時が空・パース不能な場合は安全側に倒して「途中経過」として扱う。

### 文面の違い

- **途中経過**: 従来通り。「終盤の着地予想を行ってください」という指示を含め、過度な断定を避けた推測混じりの文体にする
- **最終結果**: 開催期間が終了している旨と、最新の更新時刻が終了時刻以降である旨をシステムプロンプトの冒頭で明示し、「〜と予想されます」「〜が見込まれます」のような推測表現を禁止し、「〜という結果になりました」のように確定した事実として断定的に書かせる。あわせて、分析観点3(勢い・ペース分析)の「終盤の着地予想を行う」という締めの文言を「この結果に至るまでの終盤の展開を振り返る」に差し替える

分析観点1・2・4(順位変動・攻防/ボーダーライン攻防/新規ランクイン・圏外落ち)自体の分析内容は途中経過・最終結果で変えない(過去形で書かせるかどうかは上記の断定表現の指示でカバーする)。

## AI解説文の出力形式(2026-09-25改訂)

当初`aiSummary`は1つのフィールドを持つJSON(`{"summary": string}`)を1本の文字列として生成していたが、クライアント側でボーダーごとに表形式表示したいという要件により、ボーダーごとの解説文を配列で返す設計に変更した。`OpenAiClient.generateSummary()`はOpenAIに`{"borders": [{"border": <順位>, "summary": "<解説文>"}, ...]}`という配列形式で出力させ、これをJava側で`RaceRoundConfig`の`borderName`とマージして`List<BorderSummary>`(`border`/`borderName`/`summary`)を組み立て、`Subrace.aiSummary`に設定する。`borderName`をOpenAIの出力からそのまま使わずJava側でマージするのは、モデルの出力ゆれによる表記不一致を避けるため。

これに伴い、当初あった「1行目に【レース名 第N回 現状分析】(時点)というタイトル行を付与する」という指示は削除した。表形式で表示する前提では、このタイトル文言の置き場所が無くなったため。

## プロンプトの文面

「分析エンジン(OpenAI API)について」の方針を踏まえた、システムプロンプト・ユーザーメッセージの具体的な文面。`OpenAiClient`はレース(`RaceConfig`)+回(`RaceRoundConfig`)ごとにこの文面を組み立ててOpenAI APIを呼び出す。

### システムプロンプト

`{...}` の部分はJavaが呼び出しごとに`RaceConfig`/`RaceRoundConfig`/`PromptPayload`の値から埋め込む。

```
あなたはドラゴンクエストXのランキングイベント「{レース名}」の実況解説者です。
以下のJSON形式のランキングデータ(直近{スナップショット数}回の更新分、各ボーダー順位の前後20位を抽出したもの)を分析し、ボーダーごとに現在の状況を解説する文章を作成してください。
{開催期間の一文。途中経過/最終結果で出し分ける。詳細は「途中経過/最終結果の出し分け」を参照。途中経過の場合:}
このラウンドの開催期間は{開催開始日時}から{開催終了日時}までです。最新の更新時刻({最新更新の日時})が期間全体のどの時点に当たるかを踏まえて、終盤の着地予想を行ってください。
{最終結果の場合:}
このラウンドの開催期間は{開催開始日時}から{開催終了日時}までで、既に終了しています。最新の更新時刻({最新更新の日時})は終了時刻以降であるため、これは途中経過ではなく最終結果です。「〜と予想されます」「〜が見込まれます」のような進行中を前提にした推測表現は使わず、「〜という結果になりました」のように確定した事実として断定的に記述してください。

## 入力データの形式
- snapshots: 時系列順(古い→新しい)の更新データの配列(1件が1回の順位更新に対応)
  - time: 更新日時(ISO8601)
  - rankList: その更新時点における順位データの配列(rank/point/name/id)
    - 抽出範囲は各ボーダー({ボーダー順位一覧}位)の前後20位(このレースの追跡順位数でクランプ)を統合したものであり、全順位が含まれるわけではない
- pointは内部処理用の生値であり、画面表示用の値ではありません。本文中で得点に言及する際は、必ず「生値 × {pointScale}」を計算し、小数点以下{pointDecimalPlaces}桁に丸めた上で、末尾に単位「{pointUnit}」を付けて表記してください(得点差分・増加量などpointから導出する数値も同様に変換すること)。例えば生値が13090であれば「{生値13090をpointScale/pointDecimalPlaces/pointUnitで変換した例}」のように表記し、「13090{pointUnit}」のように生値をそのまま数値として書いてはいけません
- 変換後の数値の桁が大きい場合は、桁数を読み取りやすくするため「10,000,000」のようなカンマ区切り表記、または「0.1億」のような万・億単位の表記のいずれかを用いてください。区切りの無い「10000000」のような表記は避けてください
- nameが「(ないしょ)」のプレイヤーは匿名希望者です。名前を明かさず「(ないしょ)」のまま、または「匿名の参加者」のように言及してください。idで同一人物として扱って構いませんが、実名は絶対に出力しないでください
- idはデータ上でプレイヤーを同一人物として追跡するための内部識別子です。分析の際に同一人物判定に使うのは構いませんが、出力する文章には「id=0」のようにid自体を書かないでください。プレイヤーに言及する際はnameのみ(匿名の場合は「(ないしょ)」または「匿名の参加者」)を使ってください

## 分析観点(以下の4つの観点で分析すること)
{分析観点の4項目。累積スコア型(predictionType=0)かハイスコア型(predictionType=1)かで文言を出し分ける。詳細は「分析観点のレース種別による出し分け」を参照。累積スコア型の場合の文言は以下の通り:}
1. 順位変動・攻防: 隣接する更新間でidの順位変化(オーバーテイク)を検出する。特に1位争いの得点差の推移に注目する
2. ボーダーライン攻防: {ボーダー順位一覧}位それぞれについて、得点の推移(直近{スナップショット数}回の更新でどれだけ伸びたか)・保持者(id)の入れ替わり回数・次点との得点差(僅差かどうか)を分析する
3. 勢い・ペース分析: 更新間のtimeの差とpointの差から、時間あたりの得点増加率を計算し、急上昇・失速・頭打ち(複数回の更新にわたり得点が変化していない場合)を判定する
4. 新規ランクイン/圏外落ち: ある更新の抽出範囲に存在したidが、次の更新の抽出範囲に存在しない場合、新規ランクインまたは圏外落ちの可能性がある。ただし抽出範囲外に出ただけの可能性もあるため断定は避け、「圏外に去った可能性があります」のように書く

## 出力形式
説明や前置きなしで、以下の1つのフィールドのみを持つJSONオブジェクトのみを出力してください。判定に使った根拠データや分析過程そのものは出力に含めないでください。

{"borders": [{"border": <ボーダー順位の数値>, "summary": "<そのボーダーの解説文>"}, ...]}

bordersの各要素は、以下の順位について順序通りに1つずつ作成してください: {ボーダー順位一覧}
各summaryは以下の条件に従ってください({途中経過の場合は「文体は解説風、過度な断定は避ける」、最終結果の場合は「文体は解説風。このラウンドは終了しているため、推測表現は使わず確定した結果として断定的に書くこと」を埋め込む}):
- 300字以内に収める
- idの値そのもの(例: id=0)は書かないこと
- 入力データの項目名(snapshots等)にかかわらず、文中では「スナップショット」という語を使わず、代わりに「更新」という語を使うこと
```

(2026-09-25時点の実行結果を踏まえた改訂の経緯: 当初は末尾に総評を置く構成だったが、冒頭に総評を配置しその後にボーダーごとの分析を続ける構成に変更し、その後総評はレース状況の要約として実用上の価値が薄いと判断され段落自体を削除した。出力にidの値がそのまま含まれる不具合が実データで確認されたため、idを本文に出力しない旨の指示を明記した。「スナップショット」はツール内部の用語であり実況解説の文体に馴染まないため、プロンプト中の説明文言・分析観点の文言を「更新」に置き換え、出力時にも同語を使わないよう明示した。さらに、クライアント側でボーダーごとに表形式表示したいという要件により、出力を単一文字列からボーダーごとの配列に変更し(上記「AI解説文の出力形式」参照)、あわせて開催期間の情報も加えた。ボーダーごとの解説文の文字数上限は140字では短すぎたため300字に拡張した。また、累積スコア型/ハイスコア型(`predictionType`)によって分析観点の文言を出し分けるようにし(上記「分析観点のレース種別による出し分け」参照)、ハイスコア型の人数分析は「直近5回全体の累計」ではなく「前回更新時からの増加人数」に粒度を絞った。さらに、最新の更新時刻がラウンドの終了時刻以上になった場合は途中経過ではなく最終結果としての断定的な文体に切り替えるようにした(上記「途中経過/最終結果の出し分け」参照)。フィッシングコンテストで「1309.0cm」と表記すべき値が「13090点」と誤表記される不具合が実データで見つかったため、pointの生値を表示用に変換する指示を追加した(上記「得点の表示形式」参照)。あわせて、変換後の桁数が大きい値(600,000,000P等)を読み取りやすくするため、カンマ区切りまたは万・億単位のいずれかで表記するよう指示を追加した)

### ユーザーメッセージ

`PromptPayload`(`raceKey`/`round`/`subraceIndex`/`borders`/`snapshots`)をJacksonでJSON化したものをそのまま渡す。サンプルは [prompt-data-sample.md](prompt-data-sample.md) を参照(`borders`の値は`RaceConfig.borderRanks`ではなく`RaceRoundConfig.borders`由来になった点に注意)。

### API呼び出しパラメータ

- `model`: 環境変数`OPENAI_MODEL`(未設定時`gpt-5-mini`)
- `temperature`: 付与しない(GPT-5 mini等の推論系モデルはデフォルト値(1)以外を受け付けないため。実装時にHTTP 400 `Unsupported value: 'temperature' does not support 0.3 with this model.`で判明し、当初案の0.3指定を撤回した)
- `response_format`: `{"type": "json_object"}`
- HTTPリクエストのタイムアウト(`java.net.http.HttpRequest.Builder#timeout`): 90秒(`OpenAiClient.REQUEST_TIMEOUT`)。2026-09-25改訂: 当初60秒だったが、実データでの動作確認時にサブレースの1つで`request timed out`(`aiSummaryError`)が発生したため延長した(170秒への延長を経て、最終的に90秒に調整)。gpt-5-mini等の推論系モデルは応答生成に時間がかかることがあり、特に追跡順位数が多いレース(fishing/daifugo系、1更新あたり最大92件×5更新)ほど時間がかかりやすい。タイムアウトしても既存の障害時設計方針(上記「確定した要件」参照)により主パイプラインは継続する。なお、AWS Lambda関数自体に設定されたタイムアウト(コード外のインフラ設定)がこのHTTPタイムアウトより短い場合はLambda側のタイムアウトが先に発生するため、運用者側でLambda関数のタイムアウト設定も90秒超を確保する必要がある

### 補足

- 出力JSONのスキーマは`{"borders": [{"border": number, "summary": string}, ...]}`とし、判定に使った根拠データ(得点差の数値など)は出力に含めない(解説文の中で自然文として言及される想定)
- レース名・ボーダー順位一覧・最新更新の日時は`RaceConfig`/`RaceRoundConfig`/`PromptPayload`からJava側で文字列化してシステムプロンプトに埋め込む(`RaceConfig`への表示名フィールド追加は上記「改修対象・新規クラスのまとめ」参照)。開催期間(開始・終了日時)は`RaceRoundConfig`(rankanalytics.js由来、上記「レース+回ごとの設定(race_config.txt)の取得」参照)から埋め込む
- 「分析観点」ブロックの文言は`RaceRoundConfig.predictionType`の値によって丸ごと差し替える(累積スコア型用/ハイスコア型用の2パターンをJava側で文字列として保持し、条件分岐で選択する。詳細は上記「分析観点のレース種別による出し分け」参照)
- pointの表示変換ルール(`pointScale`/`pointDecimalPlaces`/`pointUnit`)は`RaceRoundConfig`(rankanalytics.jsの`numberFormatter`由来)から埋め込む。`PromptPayload`が運ぶ`point`自体は生値のまま変更しない(詳細は上記「得点の表示形式」参照)
- 開催期間の一文・出力条件の文体指示は、最新の更新時刻(`PromptPayload`の最後の`snapshots`要素の`time`)と`RaceRoundConfig.endTime`をJavaの`LocalDateTime`として比較した結果によって丸ごと差し替える(詳細は上記「途中経過/最終結果の出し分け」参照)

## 実データによる検証・サンプル要約文

実データ(スライムレース第7回、[sample/slimerace7.json](sample/slimerace7.json))を用いた検証結果とサンプル要約文は [summary.md](summary.md) を参照。

## 未確定・要検討事項

- **判定精度の実データ検証**: モデルはGPT-5 miniに確定したが、順位変動・得点差分計算・新規/圏外検出をOpenAI自身に行わせる方式のため、実装時に実データで判定漏れ・計算ミスがないか検証する必要がある。精度が不十分な場合は`OPENAI_MODEL`環境変数で上位モデルへ切り替える
- **trinity(バトルトリニティ)**: 現在未使用のレースのため、AI解説文生成も対象外とする。2026-09-25改訂によりボーダー情報を動的取得する方式に変更したため、`trinity`はrankanalytics.jsの`RACE_CONFIG_MAP`にエントリが存在しないこと自体が「対象外」の判定として機能する(専用の除外フラグは不要になった。上記「レース+回ごとの設定(race_config.txt)の取得」参照)
