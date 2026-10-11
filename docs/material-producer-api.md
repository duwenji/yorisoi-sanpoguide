# 素材の制作元インタフェース 契約書

## 文書情報

| 項目 | 内容 |
|---|---|
| 文書ID | API-004 |
| ドキュメント種別 | API契約書 |
| 対象システム/機能 | SanpoGuide の素材（テキスト・画像・音声・映像）の制作元と、チャンネルがそれを使うときの共通のインタフェース（IF）。第三者の素材集の形式 |
| 関連Skill | 020_api-contract-design |
| 作成日 | 2026-10-11 |
| 作成者 | Claude（開発者との検討） |
| 承認者 | 開発者（2026-10-11） |
| ステータス | 承認済 |
| 版 | 1.4（承認待ち） |

## 目的・背景

[ADR-002](adr/ADR-002-material-producers.md) で、素材の制作元をチャンネルから切り出し、組み込み・第三者・チャンネルに同梱のすべての制作元を共通の IF で扱うことにした。本書はその IF と、第三者の制作元が出す素材集の形式を定める。

IF は、アプリの中のコード（Kotlin の `interface`）と、チャンネル・素材集のファイル（JSON）の両方に現れる。本書は両方に共通する「型と意味」を定め、ファイルの書き方（`channel.json` の `collections`・`source`）は API-003 1.2 で、リストへの掲載は API-002 1.2 で定める。

## スコープ・非スコープ

スコープ:
- 制作元の記述・素材の要求・素材の 3 つの型と、準備する・出す の 2 つの操作
- 属性によるアプリの方針（何を許し、何を禁じるか）
- 組み込みの制作元の一覧
- 素材の参照の書き方と、候補の順番の決まり
- 第三者の素材集のパッケージ形式・署名・利用許諾
- エラーと、機械で確かめる項目

非スコープ:
- 見せ方とその安全の決まり（API-003 F-8。変えない）
- `channel.json` への書き方の詳細（API-003 1.2、ADR-002 T-2）
- リストの形式（API-002 1.2、ADR-002 T-3）
- 人が確かめる審査の項目（審査基準、ADR-002 T-4）

## 対応元ID（トレーサビリティ）

| 対応元ID | 内容 | 対応状況 |
|---|---|---|
| ADR-002 | D-1〜D-16 | 本書で IF と形式にする |
| API-003 | `resources` の形（F-9・F-11）、F-10（取得の時期）、F-12（AI の語りとの関係） | 素材の形を引き継ぐ。F-12 は候補の順番（I-7）で表す |
| ADR-001 | C-4（身元と署名） | 制作元の署名に同じ鍵とアカウントIDを使う |

## 方針・決定事項

| No. | 論点 | 決定（2026-10-11 承認） |
|---|---|---|
| I-1 | IF の形 | 3 つの型（制作元の記述・素材の要求・素材）と 2 つの操作（準備する・出す）。どの制作元も同じ形にする |
| I-2 | 違いの表し方 | 記述の属性（`trust`・`fetch`・`exposure`・`generated`）で表す。アプリは属性を見て[方針](#方針属性ごとの許可と制限)を当てる。制作元の自己申告ではなく、組み込みはアプリのコードが、第三者は形式が属性を決める（第三者は `trust=reviewed`・`fetch=prefetch`・`exposure=none`・`generated=false` に固定） |
| I-3 | 素材の形 | API-003 の `resources` と同じ項目（`type`・中身・`credit`・`alt`・`transcript`・`durationSec`）に、出どころ（`origin`: 制作元・版・素材の ID）を加える。どの制作元から来ても、アプリは同じ形で見せ方に渡す |
| I-4 | 選び方 | 素材の ID で選ぶ（`#id`）か、題材で選ぶ（きっかけのスポット・地点・出来事）か。題材で選ぶのは、素材に `about` があるか、組み込みの制作元が題材から作れるとき |
| I-5 | 結果 | `ready`（出せた）/ `none`（この制作元には該当がない）/ `unavailable`（今は出せない。理由付き）の 3 つ。例外は投げない |
| I-6 | 準備する | チャンネルを選んだとき（とその後の Wi-Fi のとき）に呼ぶ。`prefetch` の制作元はここで全部を取得して確かめる。`onDemand`・`local` の制作元は何もしなくてよい |
| I-7 | 候補の順番 | チャンネルは 1 つの見せ方に参照を 1〜3 個並べる。アプリは上から「出す」を呼び、最初の `ready` を使う。全部 `none`/`unavailable` なら、その見せ方は飛ばす |
| I-8 | チャンネルからの指示 | 制作元は受け付ける指示（`params`）を記述に宣言する。チャンネルは宣言された指示だけを渡せる。値は列挙か、API-003 のスロットと同じ決まり（プレーンな文章、上限あり）に従う |
| I-9 | 省略形 | `channel.json` のスロット・`guide.length`・`mood.tone`・`mood.sound`・`cues[].narration`・`present[].resource` は省略形として残し、アプリが[展開の規則](#省略形の展開)で参照と指示に直す |
| I-10 | 基準で選ぶ（1.1） | チャンネルが書いた基準（`selectors`）ごとに、アプリが「選択の制作元」を 1 つ作る。記述は `trust=reviewed`・`fetch=prefetch`・`exposure=none`・`generated=false`（P-1 のまま）。詳細は[基準で選ぶ制作元](#基準で選ぶ制作元) |

## 用語

| 用語 | 意味 |
|---|---|
| 制作元 | 素材を出すもの。組み込み・第三者の素材集・チャンネルに同梱の素材の 3 種類（ADR-002 D-2） |
| 素材 | 利用者に見せる 1 つの単位。`text`・`image`・`audio`・`video` |
| 素材集 | 第三者の制作元が作り、署名して申請する素材のまとまり。審査・版・取り下げの単位 |
| 題材 | 素材が何についてのものか。スポット（OSM の ID）・地点（緯度経度と半径）・出来事 |
| 参照 | チャンネルが素材を指す書き方。制作元と、選び方（素材の ID か題材） |
| 選択の制作元 | チャンネルの基準（`selectors`）に合う素材を、リストに載っている素材集から集めて出す制作元（1.1） |
| タグ | 素材が何の話かを表す、決まった一覧の語（1.1） |

## 型

### 制作元の記述（ProducerDescriptor）

| 項目 | 型 | 内容 |
|---|---|---|
| `id` | string | 組み込みは `builtin:<名前>`。第三者は `collection:<素材集のID>`（制作元のアカウントIDは含めない。鍵の移し替えで変わらないようにするため。ADR-002 D-17）。同梱は `self` |
| `version` | int | 組み込みはアプリの版、第三者・同梱は素材集・チャンネルの版 |
| `types` | string[] | 出せる素材の種類 |
| `subjects` | string[] | 題材で選べる種類。`spot` / `point` / `event` / `none`（題材なし。ID で選ぶだけ） |
| `trust` | string | `builtin`（アプリのコード）/ `reviewed`（運用者が審査し、リストに載ったもの）/ `trial`（試用チケット。開発者モードのときだけ） |
| `fetch` | string | `local`（端末の中だけで作る）/ `prefetch`（準備するで全部取得）/ `onDemand`（出すときに取得・生成） |
| `exposure` | string | `none`（出すときに外部と通信しない）/ `subject`（出すときに題材が外部に伝わる） |
| `generated` | bool | 中身が毎回変わる（AI など）か |
| `params` | object | 受け付ける指示の名前と、値の決まり（I-8） |

### 素材の要求（MaterialRequest）

| 項目 | 内容 |
|---|---|
| `select` | `{ "id": "<素材のID>" }` か `{ "subject": <題材> }` のどちらか |
| `type` | 欲しい素材の種類（見せ方から決まる。`speak` なら `text`、`play` なら `audio`） |
| `params` | チャンネルからの指示（I-8） |
| `context` | アプリが付ける状況: 雰囲気（`Mood`）、歩いているか、回線（従量制か）、話しかけの場面。制作元は読むだけ |
| `limits` | 字数・長さ・大きさの上限（見せ方と API-003 の上限から、アプリが決める） |

### 素材（Material）

API-003 の `resources[]` と同じ項目に、次を加える。

| 項目 | 内容 |
|---|---|
| `origin` | `{ "producer": "<制作元のID>", "version": <版>, "id": "<素材のID>" }`。生成した素材は `id` の代わりに生成の日時 |
| `content` | 中身。`text`（文字列）/ `file`（端末の中の取得済みのファイル）/ `render`（アプリが描く。雰囲気の絵だけ） |
| `credit` | 必須（F-11）。AI の生成は `{ "author": "AI（<モデル名>）", "license": "generated" }` |
| `about` | 題材（任意） |

`credit`・`alt`・`transcript` の決まりは API-003 F-11 のとおり。AI の文章は、文章そのものが書き起こしになる。

### 結果（MaterialResult）

| 結果 | 意味 | アプリの動き |
|---|---|---|
| `ready` | 素材を出せた | 見せ方に渡す |
| `none` | この制作元には該当する素材がない | 次の候補へ |
| `unavailable` | 今は出せない（`offline`・`metered`・`no_key`・`revoked`・`not_prepared`・`failed`） | 次の候補へ。理由はログに残す |

## 操作

アプリの中の IF（概略）:

```kotlin
interface Producer {
    val descriptor: ProducerDescriptor
    /** チャンネルを選んだとき（と、その後の Wi-Fi のとき）に呼ぶ */
    suspend fun prepare(scope: PrepareScope): PrepareResult
    /** 見せる直前に呼ぶ。例外は投げず、結果で返す */
    suspend fun produce(request: MaterialRequest): MaterialResult
}
```

- `prepare`: `prefetch` の制作元は、チャンネルが参照する素材をすべて取得し、SHA-256 を確かめて保存する。取得できなかった素材は、出すときに `unavailable(not_prepared)` を返す。出すときに取得し直さない（F-10）
- `produce`: 1 回の呼び出しの待ち時間の上限は、`local`・`prefetch` で 1 秒、`onDemand` で 30 秒（AI の解説と同じ）。上限を超えたら `unavailable(failed)`

## 方針（属性ごとの許可と制限）

アプリが強制する。チャンネル・制作元からは変えられない。

| No. | 条件 | 方針 |
|---|---|---|
| P-1 | `trust` が `reviewed`・`trial` | `fetch=prefetch`・`exposure=none`・`generated=false` 以外は拒む（ADR-002 D-5）。素材集の形式がこれ以外を書けないので、実際には形式の確認で拒まれる |
| P-2 | `fetch=onDemand` かつ `exposure=subject` | `trust=builtin` に限る。利用者の回線の設定に従う（例: Commons の写真は既定で従量制でない回線のときだけ） |
| P-3 | `generated=true` | 共通の指示（`shared/guard`）を必ず付ける。カード・解説の画面に「AI による」と出す |
| P-4 | すべて | 見せ方の安全（F-8）、話す順番（F-13）、素材の上限（API-003）を当てる。上限を超える素材は `unavailable(failed)` と同じに扱う |
| P-5 | `trust=reviewed` | 利用者がチャンネルの提供元を信用しているだけでは使わない。その素材集が同じ提供元のリストに載り、取り下げられておらず、利用許諾がそのチャンネルの配信元を含むときだけ使う |
| P-6 | 選択の制作元（1.1） | 前もって取得するのは、端末が今いる地域とその隣の地域（geohash の 3 文字で 9 つ）に `regions` が重なる素材集と、`regions` を持たない素材集だけ。従量制でない回線のときだけ取得する。1 チャンネルの選択の制作元の合計で 50MB（映像を除く）まで。超えるときは[順番](#複数の素材が当たったとき)の上位から取る。近づいてから取得しない（F-10） |

## 組み込みの制作元

| `id` | 今のコード | 出すもの | `subjects` | `fetch` / `exposure` / `generated` | `params`（チャンネルからの指示） |
|---|---|---|---|---|---|
| `builtin:ai-guide` | `GuideRepository` | スポットの解説（`text`） | `spot` | `onDemand` / `subject` / true | `persona`・`focus`（スロット）、`length` |
| `builtin:ai-companion` | `WalkCompanion` | 散歩の友の一言（`text`） | `event` | `onDemand` / `subject` / true | `persona`・`topics`・出来事ごとの指示（スロット）、`tone` |
| `builtin:fallback-text` | `prompts/fallback/` | AI を使えないときの定型文（`text`） | `spot`・`event` | `local` / `none` / false | なし |
| `builtin:commons-photo` | `SpotPhotos` | スポットの写真（`image`、幅 800px） | `spot` | `onDemand` / `subject` / false | なし |
| `builtin:ambient` | `Soundscape`・`AmbientPlayer` | 合成の背景音（`audio`、ループ） | `none` | `local` / `none` / false | `sound`（`auto`・`rain`・`waves`・`wind`・`birds`・`insects`・`temple`） |
| `builtin:mood-scene` | `MoodScene` | 雰囲気の絵（`image`、`content=render`） | `none` | `local` / `none` / false | なし |

- 組み込みの制作元を足すのはアプリの更新だけ（ADR-002 D-6）。足したら本表に加え、本書の版を上げる
- `builtin:ai-guide` が `unavailable(no_key)` を返したとき、今と同じく定型文に移るのは、省略形の展開で `builtin:fallback-text` を候補の最後に入れることで表す

## 参照の書き方

参照は文字列で書く。

```
<制作元>            題材で選ぶ（きっかけの題材を使う）
<制作元>#<素材のID>  素材の ID で選ぶ
```

| `<制作元>` | 意味 |
|---|---|
| `builtin:<名前>` | 組み込みの制作元 |
| `self` | チャンネルに同梱の素材（今の `resources`） |
| `<別名>` | チャンネルが `collections` で宣言した素材集の別名（下の例）か、`selectors` で宣言した基準の別名（1.1。[基準で選ぶ制作元](#基準で選ぶ制作元)）。`builtin`・`self` は別名に使えない。基準の別名には `#` を付けられない |

```json
"collections": [
  { "as": "kamakura", "id": "kamakura-audio-guide", "minVersion": 2 }
],
"cues": [
  { "id": "hachimangu", "trigger": { "type": "spot", "osm": "way/123456" },
    "present": [
      { "as": "speak", "source": ["kamakura#hachimangu-intro", "builtin:ai-guide", "builtin:fallback-text"] },
      { "as": "card",  "source": ["kamakura#old-photo", "builtin:commons-photo"] } ] }
]
```

- `collections[]` は素材集の ID だけで指す。制作元のアカウントIDは書かない。制作元が誰かは、リストの `collections[].producer` で分かる（ADR-002 D-17）
- `source` は 1〜3 個（I-7）。並べた順に試す
- `#` のない参照で、きっかけに題材がない（`event` の `select` など）ときは、その参照は `none`
- 宣言した素材集で使っていないものがあれば拒む。使う素材の ID は、管理システムがチャンネルの申請時に、リストに載った版で確かめる（D-8）

## 省略形の展開

`channel.json` の今の項目は、アプリが読み込むときに次のように展開する（I-9）。展開した結果は、今の動きと同じになること（確認観点 W-01）。

| 省略形 | 展開 |
|---|---|
| `prompts/guide/*`、`guide.length` | `builtin:ai-guide` の `params`（`persona`・`focus`・`length`） |
| `prompts/companion/*`、`mood.tone` | `builtin:ai-companion` の `params` |
| `mood.sound` | 背景音: `builtin:ambient` の `params.sound` |
| スポットの解説（きっかけがないスポット） | `source: ["builtin:ai-guide", "builtin:fallback-text"]` |
| 解説の画面の写真 | `source: ["builtin:commons-photo"]` |
| `present[].resource: "x"` | `source: ["self#x"]` |
| `cues[].narration: "replace"` | AI の解説を出さない（きっかけの `source` だけを使う） |
| `cues[].narration: "add"` | きっかけの `present` の前に `{ "as": "speak", "source": ["builtin:ai-guide", "builtin:fallback-text"] }` を足す |

## 基準で選ぶ制作元

1.1 で追加（ADR-002 D-23〜D-28）。チャンネルは、素材集・素材を指す代わりに、選ぶ基準を書ける。

```json
"selectors": [
  { "as": "history-audio",
    "match": { "type": "audio", "tags": ["history", "faith"], "lang": "ja", "maxDurationSec": 180 },
    "producers": ["sg1abcd..."] }
]
```

| 項目 | 内容 |
|---|---|
| `as` | 別名。`collections` の別名と同じ決まりで、重ねない |
| `match.type` | 素材の種類 1 つ（必須） |
| `match.tags` | [タグ](#タグ)を 1〜5 個（必須）。どれか 1 つを持つ素材が当たる |
| `match.lang` | 言語（任意。既定はチャンネルの `lang`） |
| `match.maxDurationSec` | `audio`・`video` の長さの上限（任意） |
| `match.subject` | `trigger`（既定。きっかけの題材についての素材だけ）/ `none`（題材を持たない素材だけ。出来事のきっかけ用） |
| `producers` | 優先する制作元のアカウントIDの順番（任意。0〜10 個）。ここにない制作元も対象になる |

- 対象は、同じ提供元のリストに載っていて、取り下げられておらず、`grants` がチャンネルの配信元を含む素材集だけ（D-27）
- 宣伝の素材集（リストの `promotional`。ADR-002 1.2 D-33）は対象にしない。宣伝の素材集は、チャンネルが `collections` で宣言して名指ししたときだけ使える
- 利用者が設定で「宣伝の素材を出さない」をオンにしたときは、宣伝の素材集の素材と、宣伝のチャンネルの同梱の素材を、出せないものとして次の候補へ進む（I-7、D-34）
- 前もっての取得の範囲は方針 P-6

### 複数の素材が当たったとき

1. `producers` に書かれた制作元（書かれた順）
2. 確認済みの制作元（ADR-002 の未決事項 No.6 で印を付けることにした場合）
3. 素材集の承認日の新しい順、同じ素材集の中では `resources` の順

同じ散歩の中では、同じスポット・地点に対して最初に出した素材を出し続ける（散歩ごとに変わることはある）。

### タグ

| タグ | 意味 |
|---|---|
| `history` | 歴史・人物・出来事 |
| `faith` | 寺社・信仰・祭祀 |
| `architecture` | 建物・町並み・遺構 |
| `nature` | 植物・鳥・虫・水辺 |
| `landscape` | 景色・地形・眺め |
| `season` | 季節の行事・風物 |
| `festival` | 祭り・催し |
| `food` | 名物・食文化 |
| `craft` | 工芸・ものづくり・商い |
| `art` | 美術・文学・映画の舞台 |
| `folklore` | 伝説・言い伝え・地名の由来 |

タグを足すのは互換性のある変更（本書の版を上げずに表に足す）。アプリが知らないタグは、照合で当たらないだけで、拒まない。

## 素材集の形式（第三者）

ZIP（無圧縮・Deflate のみ）。

```
collection.json        設定（必須）
signature.json         制作元の署名（必須。API-003 と同じ形式）
resources/
├── text/*.md
└── img/*.jpg・*.png・*.webp
```

```json
{
  "format": 1,
  "id": "kamakura-audio-guide",
  "version": 2,
  "producer": "sg1abcd...",
  "name": "鎌倉音声ガイド",
  "summary": "鎌倉市観光協会による寺社の音声ガイド",
  "lang": "ja",
  "grants": { "publishers": "*" },
  "resources": [
    { "id": "hachimangu-intro", "type": "text", "file": "resources/text/hachimangu.md", "tags": ["history", "faith"],
      "about": { "type": "spot", "osm": "way/123456", "name": "鶴岡八幡宮", "lat": 35.3260, "lon": 139.5565 } },
    { "id": "hachimangu-audio", "type": "audio",
      "url": "https://cdn.example.com/kamakura/hachimangu.ogg", "sha256": "<hex>", "size": 2480000,
      "durationSec": 154, "mime": "audio/ogg", "transcript": "hachimangu-intro",
      "credit": { "author": "鎌倉市観光協会", "license": "permission" },
      "about": { "type": "spot", "osm": "way/123456", "name": "鶴岡八幡宮", "lat": 35.3260, "lon": 139.5565 } }
  ]
}
```

| 項目 | 内容 |
|---|---|
| `format` | 本書は `1` |
| `id` | 英小文字・数字・`-`、3〜40 文字。提供元の中で一意。最初に登録した制作元が持ち、取り下げ・退会のあとも再利用しない（ADR-002 D-17）。チャンネルの ID とは別の名前空間 |
| `version` | 1 以上 |
| `producer` | 制作元のアカウントID（`sg1…`）。`signature.json` の公開鍵から計算した値と一致し、リストの `collections[].producer` とも一致すること（API-003 F-6 と同じ確かめ方）。鍵を移し替えたあとの版は新しいアカウントIDで署名する。前の鍵で署名した承認済みの版は、リストが同じ素材集の版として載せている限り使える |
| `name` / `summary` / `lang` | API-003 の `channel.json` と同じ決まり |
| `grants.publishers` | 使ってよい配信元。`"*"`（誰でも）か、アカウントIDの配列（1〜50 個）（ADR-002 D-9） |
| `resources[]` | API-003 の `resources[]` と同じ項目・上限。加えて `about`（任意） |
| `resources[].about` | 題材（`name` は 1〜60 文字。1.2）。`{ "type": "spot", "osm": …, "name"?: …, "lat": …, "lon": … }`（1.3: スポットにもおおよその位置を必須。スポットの中か、その境界から 100m 以内の点。リストの `regions` の計算と、ID が違っても同じ名前で 200m 以内なら同じスポットとみなす照合に使う） か `{ "type": "point", "lat", "lon", "radiusM" }`。照合の規則は API-003 のきっかけと同じ |
| `resources[].tags` | （1.1）[タグ](#タグ)を 0〜3 個。基準で選ぶときに使う。タグのない素材は基準では当たらない（ID・題材での参照には使える）。書式は英小文字で始まる英小文字・数字・`-` の 1〜30 文字（1.2）。一覧にないタグは拒まない |

- `signature.json`（1.2 で明記）: API-003 と同じ形（`payload`・`publisherKey`・`sig`）。`payload` は `{ "type": "collection-package", "collection": "<素材集のID>", "version": <版>, "producer": "<アカウントID>", "files": { "<パス>": "<SHA-256 の hex>" } }`。`type` が違う署名（チャンネルの署名など）は拒む
- 素材は 1 つ以上（空の素材集は拒む）。`resources/` の下に置けるのは、`file` で指されたファイルだけ（1.2 で明記）
- 素材集の大きさの上限は、API-003 のチャンネル 1 つ分と同じ（外の素材の合計 200MB、映像を除いて 50MB、パッケージ 2MB（API-002））。1 つのチャンネルが参照する素材集すべてと同梱の素材を合わせた前もっての取得も、映像を除いて 50MB まで
- 素材は 1 つの素材集で 500 個まで
- 更新で、公開中のチャンネルが参照している素材の ID を消す・`type` を変えることはできない（管理システムが拒む。ADR-002 D-8）

## 確かめる手順（アプリ・管理システム共通）

1. 素材集: ZIP の展開、`collection.json` の形式、ファイルの許可（API-003 F-5 と同じ）、署名と `producer` の一致
2. 素材集: リストの `collections[]` の SHA-256・版と一致すること（API-002 1.2）
3. チャンネル: `collections` の各素材集が、同じ提供元のリストにあり（取り下げられておらず）、版が `minVersion` 以上で、`grants` がチャンネルの `publisher` を含むこと。配信元・制作元の鍵の移し替えがあったときは、リストの `publisherChange`・`producerChange` で前のアカウントIDを今のものに読み替えて比べる
4. チャンネル: `source` の参照がすべて解決できること（制作元が存在し、`#id` の素材がその版にあり、種類が見せ方に合うこと）
5. 組み込みのチャンネル: 3・4 を、内蔵の提供元のリストで確かめる。解決できない参照は、その候補だけを飛ばす（組み込みのチャンネル全体は拒まない）

## エラー表

| コード | 起きる場面 | 扱い |
|---|---|---|
| `collection_invalid` | 手順 1 の失敗 | 素材集を取り込まない |
| `collection_not_listed` | 手順 2・3 で、リストにない・版が古い・取り下げ | その素材集を参照する候補は `unavailable(revoked)` |
| `not_granted` | 手順 3 で、`grants` が配信元を含まない | 管理システムはチャンネルの申請を拒む。アプリは候補を `unavailable(revoked)` |
| `unresolved_reference` | 手順 4 の失敗 | 管理システムは申請を拒む。アプリはチャンネルを拒む（`package_rejected`） |
| `policy_violation` | P-1 に反する記述 | 取り込まない |

## 確認観点

| No. | 観点 |
|---|---|
| W-01 | 組み込みのチャンネル 6 本を省略形の展開で読み込み、今と同じ話しかけ・解説・写真・背景音になる |
| W-02 | 候補の順番: 1 つ目が `none`・`unavailable` のとき 2 つ目、全部だめなら見せ方を飛ばす |
| W-03 | AI のキーがないとき、解説が定型文になる（今と同じ） |
| W-04 | 従量制の回線では `builtin:commons-photo` が `unavailable(metered)` になる（今と同じ） |
| W-05 | `trust=reviewed` の制作元が `onDemand`・`exposure=subject` を宣言できない（形式で書けない） |
| W-06 | 素材集の素材を、出すときに取得しない（`prepare` で取得したものだけを出す） |
| W-07 | `grants` に含まれない配信元のチャンネルが、その素材集を使えない |
| W-08 | 取り下げた素材集を使うきっかけが次の候補に進み、チャンネルは続く |
| W-09 | 公開中のチャンネルが使う素材の ID を消した更新を、管理システムが拒む |
| W-10 | 署名の `producer` と `collection.json` の `producer` が違う素材集を拒む |
| W-11 | AI の生成した素材に「AI による」と出る |
| W-12 | `about` で選ぶ参照が、スポットの ID が違っても同じ名前で 200m 以内なら当たる |
| W-13 | 制作元の鍵を移し替えたあとも、チャンネルの参照が解決できる（`producerChange`）。`grants` に前のアカウントIDの配信元が書かれていても、読み替えて確かめる |
| W-14 | 制作元の退会で素材集が取り下げられたとき、使っているきっかけは次の候補に進み、チャンネルは続く |
| W-15 | （1.1）基準の `type`・`tags`・`lang`・`maxDurationSec`・`subject` に合う素材だけが当たる |
| W-16 | （1.1）`grants` がチャンネルの配信元を含まない素材集の素材は、基準に合っても当たらない |
| W-17 | （1.1）複数当たったとき、`producers` の順 → 承認日の新しい順で選ばれ、同じ散歩では同じスポットで変わらない |
| W-18 | （1.1）前もって取得するのは、今いる地域と隣の地域の素材集と、`regions` を持たない素材集だけ。従量制の回線では取得しない。スポットに近づいたときに通信しない |
| W-19 | （1.1）選択の制作元の取得が 50MB を超えるとき、順番の上位から取る |

## 互換性方針・移行メモ

- 新しい契約。`channel.json` は省略形のまま読めるので、API-003 1.1 のチャンネルはそのまま動く
- `collections`・`source` を使うチャンネルは古いアプリで拒まれる（知らない項目は無視されるが、`source` だけの `present` は `resource` がないので形式の誤りになる）。API-002 の `minAppVersion` で案内する
- 組み込みの制作元を足すのは互換性のある変更。古いアプリで知らない `builtin:` は `none` として扱い、次の候補に進む
- 方針（P-1〜P-5）を緩める変更は、ADR-002 の見直しの条件にあたる
- リストの `collections[]`・`producerChange` の形は API-002 1.2 で定める（ADR-002 T-3）

## 制約

- 素材の中身・題材の正しさは機械では確かめられず、人の審査に頼る
- 題材での照合は OSM の ID と名前に頼るので、OSM のデータが変わると当たらなくなることがある

## 未決事項・リスク

| No. | 内容 | 決める時期 |
|---|---|---|
| 1 | ~~方針 I-1〜I-9・P-1〜P-5 の採否~~ → 解決（2026-10-11、すべて採用） | — |
| 2 | `source` の候補を 3 個までとするのが足りるか | 第三者のチャンネルの見本を作るとき |
| 3 | ~~素材集を題材でまとめて出す一括のきっかけ~~ → 解決（1.1、基準で選ぶ制作元と、API-003 1.2 の題材を問わないきっかけ） | — |
| 4 | 素材集の素材を AI に渡すか（ADR-002 の未決事項 No.1）。渡す場合は `params` ではなく別の型にする | アプリの素材の第 1 段の後 |
| 5 | 試用チケットで素材集も試せるようにするか（`trust=trial`） | 管理システムの改訂（ADR-002 T-6） |
| 6 | ~~題材がスポットの素材は位置を持たないため、管理システムがリストの `regions` を計算できない~~ → 解決（1.3、2026-10-11 開発者の選択）。`about` のスポットにおおよその `lat`・`lon` を必須で足す。管理システムが OSM から位置を引く案は、公開の Overpass サーバーに頼り、審査のたびに遅く失敗しやすいため採らない | — |

## 関連ドキュメント・参照リンク

- [ADR-002](adr/ADR-002-material-producers.md): 制作元を切り出す判断
- [channel-package-format.md（API-003）](channel-package-format.md): 素材・きっかけ・見せ方
- [channel-list-api.md（API-002）](channel-list-api.md): 承認済みチャンネル・リスト
- [channels.md](channels.md): アプリ側の設計

## 変更履歴

| 日付 | 版 | 変更内容 | 変更者 |
|---|---|---|---|
| 2026-10-11 | 0.1 | 草案 | Claude |
| 2026-10-11 | 0.2 | 素材集を ID だけで参照するように変更（記述の `id`、`collections[]`、素材集の `id` の一意の範囲）。鍵の移し替えの読み替え（手順 3）、確認観点 W-13・W-14 を追加（ADR-002 D-17〜D-19） | Claude |
| 2026-10-11 | 1.0 | 承認（I-1〜I-9・P-1〜P-5 を採用） | Claude（承認: 開発者） |
| 2026-10-11 | 1.1 | 基準で選ぶ制作元を追加（I-10、P-6、「基準で選ぶ制作元」の章、タグの一覧、素材の `tags`、確認観点 W-15〜W-19）。ADR-002 1.1 による | Claude |
| 2026-10-11 | 1.1 | 承認 | Claude（承認: 開発者） |
| 2026-10-11 | 1.2 | 素材集の `signature.json` の `payload` の形、空の素材集を拒むこと、タグの書式、`about.name` の長さを明記（station-format の実装で決めた値。ADR-002 T-5a） | Claude |
| 2026-10-11 | 1.2 | 承認 | Claude（承認: 開発者） |
| 2026-10-11 | 1.3 | 題材がスポットの `about` に `lat`・`lon` を必須で足す（未決事項 No.6 の解決。開発者の選択による）。リストの `regions` はすべての題材の位置から計算できる | Claude（承認: 開発者） |
| 2026-10-11 | 1.4 | 宣伝の素材集を基準で選ばないこと、利用者の設定で宣伝の素材を飛ばすことを明記（ADR-002 1.2 D-33・D-34） | Claude |
