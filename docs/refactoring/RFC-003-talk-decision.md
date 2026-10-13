# RFC-003 段階 2: 「いま何を話すか」の判定を取り出し、kit-companion のエンジンにする

## 文書情報

| 項目 | 内容 |
|---|---|
| 文書ID | RFC-003 |
| ドキュメント種別 | リファクタリング計画書 |
| 対象システム/機能 | SanpoGuide の `WalkService.maybeTalk()` と、その判定の部品（`SpotChooser`・`FacilityAdvisor`・`WeatherChangeDetector`・`TalkLevel`）。ADR-003 の段階 2（T-2・T-3） |
| 関連Skill | refactoring-safety |
| 作成日 | 2026-10-12 |
| 作成者 | Claude（開発者との検討） |
| 承認者 | 開発者（1.0・1.1: 2026-10-12。実施結果は PR でレビュー） |
| ステータス | 実施済（2026-10-12） |
| 版 | 1.2 |

## 目的・背景

[ADR-003](../adr/ADR-003-companion-app-platform.md) E-7 で、「いま何を話すか」の判定を、優先順位・安全かどうか・守る間隔を持つ**ルールの並び**として表し、調停を `kit-companion` に置くことにした。2 本目のアプリ（サイクリングの伴走。E-23）は、散歩とは違うルール（速度で変わる間隔、走行中の制限）を、同じ調停の上に並べることになる。

今の判定（`WalkService.maybeTalk()`、約 150 行）には次の問題がある。

1. **判定と実行が混ざっている**: 1 つの関数の中で、判定の途中に読み上げ・通知・記録の保存・セッションの更新を行っている
2. **テストがない**: Android のサービスの中にあり、`app` の部品（`speaker`・`stations`・`settings`・`spots`・`cueFinder`・`history`）を直接読むため、判定の順番と条件を単体テストで確かめられない（RFC-002 の未決事項 No.1 で段階 2 に回した）
3. **順番の決まりがコードの並びにしかない**: 「天気の急変は頻度を無視」「切り替えの直後 1 分はスポットと雑談を控えるが施設の案内は控えない」などの決まりが、`return` と `if` の並びで表されている（architecture.md 図 2 は、この並びを手で書き起こしたもの）

調べたところ（2026-10-12）、判定に使うものは読むだけで済む。きっかけの照会（`CueFinder.spot`・`point`）は読むだけで、出したことの記録は `CueFinder.record` が別に行う。過去の訪問（`HistoryStore.visitsTo`）も読むだけ。したがって判定は「その時の状況 → 決定」の純粋な関数として取り出せる。

**アプリの動きは変えない。**

## 対応元ID（トレーサビリティ）

| 対応元ID | 内容 | 対応状況 |
|---|---|---|
| ADR-003 T-2 | 判定順の場面テスト | 手順 1 |
| ADR-003 T-3 | 話す判断のエンジン（`kit-companion`） | 手順 3〜4 |
| ADR-003 E-7 | ルールの並びと調停 | 本書で設計する |
| ADR-003 E-23 | サイクリングの判定も表せること | 手順 3 の設計で確かめる（作りはしない） |
| RFC-002 未決事項 No.1 | `maybeTalk()` の場面テスト | 本書で行う |

## 方針・決定事項

| No. | 論点 | 決定 |
|---|---|---|
| D-1 | 進め方 | 2 段に分ける。**段 A（手順 1・2）**: 判定をアプリの中で純粋な関数 `TalkDecider.decide(TalkSituation): TalkDecision` に取り出し、場面テストを付ける。**段 B（手順 3・4）**: 取り出した判定をルールの並びに書き直し、調停を `kit-companion` に移す。段 A だけでも、判定にテストが付く価値がある |
| D-2 | 状況（入力） | `TalkSituation` は判定に要る値だけを持つ**値のスナップショット**にする。`WalkService` が毎回集めて渡す。`android.location.Location` や `WalkSession` そのものは渡さない（単体テストで作れないため）。距離・休んでいる分数・経過時間・前回の発話からの時間などは計算済みの値で渡す |
| D-3 | 決定（出力） | `TalkDecision` は今の分岐と 1 対 1 の sealed 型にする: `WeatherTurn`（天気の急変）、`Greeting`（切り替えのあいさつ）、`SpotCue`（スポットのきっかけ）、`Spot`（スポットの案内。初訪問・再訪は `SpotCandidate.visitedBefore`）、`PointCue`（地点のきっかけ）、`Sunset`、`Facility`、`Rest`、`Milestone`、`Checkpoint`（区切りで話さないチャンネルの記録の保存だけ）、`None` |
| D-4 | 実行 | `WalkService` は「状況を集める → `decide` → 決定を実行する」だけにする。実行（読み上げ・通知・記録・セッションの更新・施設の方向の計算・きっかけの記録）は今のコードをそのまま `when (decision)` の各枝に移す。話している最中のロック（`talk {}` の `tryLock`）と読み上げ中の判定の省略も実行の側に残す。決定を実行できなかったとき（ロックが取れない）は、今と同じく何もしない |
| D-5 | きっかけの照会 | `TalkSituation` には、近くのスポットごと・現在地のきっかけの照会結果（`CueFire?`）を `WalkService` が先に引いて入れる。照会は読むだけなので、先に引いても動きは変わらない。照会の数はスポット案内の範囲（60m）の中のスポットの数だけ（今もその数だけ引いている） |
| D-6 | 場面テスト | 手順 1 で、取り出した `decide` に対して、場面（状況）と期待する決定を並べた表のテストを書く。少なくとも architecture.md 図 2 の 8 つの判断それぞれについて「出る場面」「出ない場面（間隔・頻度・チャンネルの設定・切り替えの直後・とどまっている・読み上げ中）」と、優先順位がぶつかる場面（天気の急変とスポット、あいさつとスポット、日の入りと施設、など）を入れる |
| D-7 | エンジンの形（段 B） | `kit-companion` は、アプリが決める状況の型 `S` と決定の型 `D` に対して、`TalkRule<S, D>` の並びを評価する `TalkEngine<S, D>` を持つ。ルールは判定（`(S) -> D?`）と、調停が見る属性を持つ: `safety`（頻度・チャンネルの設定・雑談の間隔を無視する）、`spacing`（守る間隔: なし／スポットの間隔／雑談の間隔。値は状況から求める）、`quietAfterSwitch`（切り替えの直後は控える）、`whileStationary`（とどまっている間も出すか）。エンジンは上から順に評価し、最初に決定を返したルールを採る。散歩のルール（施設・スポットの選び方・日の入り…）と属性はアプリに置く |
| D-8 | サイクリングで確かめること（段 B の設計のみ） | 次がルールと属性で書けることを、手順 3 の設計レビューで確かめる（実装はサイクリングのアプリのとき）: 速度で変わる間隔（`spacing` を状況から求める）、走行中は話さない種類のルール（判定の中で速度を見る）、止まっていないと出せない見せ方（これは判定ではなく見せ方の決まりなので、エンジンの外。API-003 F-8 と同じ扱い）。書けないと分かったら、手順 3 で属性を足す |
| D-9 | 動きを変えないことの保証 | 段 A: 手順 1 の場面テストが通り、エミュレータで散策を通しで確かめる。段 B: 手順 1 の場面テストを書き換えずに通す（テストは `decide` の入出力だけを見るので、中身をエンジンに替えても同じテストが使える） |

### 手順と保証

| 手順 | 内容 | 保証 |
|---|---|---|
| 1 | `TalkSituation`・`TalkDecision`・`TalkDecider.decide` をアプリの `companion` に作り、`maybeTalk()` の判定をそこへ移す。`maybeTalk()` は状況を集めて決定を実行するだけにする（D-2〜D-5）。場面テストを書く（D-6） | RFC-002 の G-1〜G-5（テストの数は場面テストの分だけ増える）。場面テストが通る。エミュレータ（AVD `sanpo30`、鎌倉）で、散策の開始・スポットの案内・チャンネルの切り替えのあいさつ・時刻を進めたときの施設の案内・終了を確かめる |
| 2 | architecture.md 図 2 を、`TalkDecider` の決定の並びとして書き直す（図とコードの並びがずれないよう、図に `TalkDecider` を指す） | 文書だけ |
| 3 | 設計レビュー: `TalkRule`・`TalkEngine` の形（D-7）と、サイクリングで確かめること（D-8）を、この RFC の改訂として開発者に見せる | レビュー |
| 4 | `platform/kit-companion`（`io.github.duwenji.yorisoi.kit.companion`。JVM で足りれば JVM、`Flow` などが要れば Android）を作り、`TalkDecider` をルールの並びとエンジンで書き直す | 手順 1 の場面テストを書き換えずに通す。エミュレータで手順 1 と同じ確認 |

### 手順 3: エンジンの設計（1.1、2026-10-12 承認）

手順 1 で取り出した判定を整理すると、判定ごとの「出してよいか」は、次の 4 つの条件の組み合わせだけで決まっていた（`TalkDecider.decide` の `if` の並びから読み取ったもの）。

| 判定 | スポットの間隔 | 雑談の間隔 | とどまっていない | 切り替えの直後でない | チャンネルで止められない |
|---|:-:|:-:|:-:|:-:|:-:|
| ① 天気の急変 | | | | | ○ |
| ② 切り替えのあいさつ | | | | | |
| ③ スポットのきっかけ・スポット | ○ | | ○ | ○ | |
| ④ 地点のきっかけ | | | ○ | ○ | |
| ⑤ 日の入り | | | | | ○ |
| ⑥ 施設 | | ○ | | | ○ |
| ⑦ 休憩 | | ○ | | ○ | |
| ⑧ 区切り（と記録だけ） | | ○ | | ○ | |

そこで、D-7 の属性（`spacing`・`quietAfterSwitch`・`whileStationary`）を、**ゲート**という 1 つの考え方にまとめる。

| No. | 論点 | 決定 |
|---|---|---|
| D-10 | ゲート | 「今その種類の発話を出してよいか」を表す、アプリが決める列挙。SanpoGuide は `SPOT_GAP`（前回のスポットの話から `TalkLevel.spotGapMs` 経った）・`CHAT_GAP`（前回の発話から `minGapMs` 経った）・`MOVING`（とどまっていない）・`SETTLED`（切り替えの直後でない）の 4 つ。状況（`TalkSituation`）が、各ゲートが今開いているかを答える。間隔の長さや「とどまっている」の基準はアプリの値で、エンジンは知らない（D-9 の JVM で足りる理由） |
| D-11 | ルール | `TalkRule<S, D, G>(name, gates: Set<G>, safety: Boolean, decide: (S) -> D?)`。`gates` がすべて開いているときだけ `decide` を呼ぶ。`decide` は、ルール固有の条件（チャンネルの「話す場面」、天気の予告の繰り返しの間隔、日の入りの時間帯、施設の条件など）を見て、出すなら決定を、出さないなら null を返す |
| D-12 | エンジン | `TalkEngine<S, D, G>(rules, nothing: D)`。`decide(s)` は上から順に、ゲートが開いているルールの `decide` を呼び、最初に null 以外を返したものを採る。どれも返さなければ `nothing`。それ以外のことはしない（値も状態も持たない） |
| D-13 | `safety` | 「チャンネルで止められない」ことの**宣言**（ADR-003 E-6 ②・E-13）。エンジンの調停は変えない（施設の案内は安全側だが、雑談の間隔は守る。今の動きのとおり）。宣言は、プロファイルが止められない出来事を列挙するとき（ADR-003 E-6）と、テスト（「チャンネルの話す場面を空にしても safety のルールは出る」を全ルールについて確かめる。V-08 の一般化）に使う |
| D-14 | モジュール | `platform/kit-companion` は JVM のモジュール（Android に依存しない）。中身はエンジン・ルール・ゲートの型だけ。SanpoGuide のルールの並び・ゲート・状況はアプリに置く |

SanpoGuide のルールの並び（手順 4 で `TalkDecider` をこれに置き換える）:

```kotlin
enum class WalkGate { SPOT_GAP, CHAT_GAP, MOVING, SETTLED }

val walkRules = listOf(
    TalkRule("weather", gates = emptySet(), safety = true) { s -> weatherTurn(s) },
    TalkRule("greeting", gates = emptySet()) { s -> s.pendingGreeting?.let { Greeting(it, s.pendingSelect) } },
    TalkRule("spot", gates = setOf(SPOT_GAP, MOVING, SETTLED)) { s -> spotCue(s) ?: spot(s) },
    TalkRule("point", gates = setOf(MOVING, SETTLED)) { s -> s.pointCue?.let(::PointCue) },
    TalkRule("sunset", gates = emptySet(), safety = true) { s -> sunset(s) },
    TalkRule("facility", gates = setOf(CHAT_GAP), safety = true) { s -> facility(s) },
    TalkRule("rest", gates = setOf(CHAT_GAP, SETTLED)) { s -> rest(s) },
    TalkRule("milestone", gates = setOf(CHAT_GAP, SETTLED)) { s -> milestone(s) },   // Milestone か Checkpoint
)
```

今の判定との対応: `if (now - s.lastSpokeAt < level.minGapMs) return Nothing` は、その後のルールがすべて `CHAT_GAP` を持つことと同じ。`if (s.justSwitched) return Nothing` も、その後のルールがすべて `SETTLED` を持つことと同じ。したがって手順 1 の場面テストは、書き換えずに通るはず（D-9）。

**サイクリングで確かめること（D-8）**: 次のように書ける（作るのはサイクリングのアプリのとき）。

```kotlin
enum class RideGate { CHAT_GAP, SLOW, SETTLED }

// 速度で変わる間隔: 間隔の長さは状況（アプリの値）が決める。エンジンは「開いているか」だけを聞く
override fun isOpen(g: RideGate) = when (g) {
    RideGate.CHAT_GAP -> now - lastSpokeAt >= if (speedKmh >= 20) 10 * MINUTE else 4 * MINUTE
    RideGate.SLOW -> speedKmh < 8          // 信号待ちや押し歩きのときだけ
    RideGate.SETTLED -> !justSwitched
}

val rideRules = listOf(
    TalkRule("weather", emptySet(), safety = true) { ... },            // 走行中でも知らせる
    TalkRule("sunset", emptySet(), safety = true) { ... },
    TalkRule("landmark", setOf(CHAT_GAP, SETTLED)) { ... },            // 走りながら聞ける短い一言
    TalkRule("story", setOf(CHAT_GAP, SLOW, SETTLED)) { ... },         // 長い解説は止まっているときだけ
)
```

- 速度で変わる間隔 → ゲートの判定（アプリ側）で書ける
- 走行中は話さない種類 → そのルールに `SLOW` のゲートを付けて書ける
- 走行中は画面を出さない → 見せ方の決まりで、話す判断の外（API-003 F-8 と同じく、見せる側が強制する）。エンジンには入れない

書けないものは見つからなかった。属性を足す必要はない。

### 実施の記録

| 手順 | 日付 | 結果 |
|---|---|---|
| 1 | 2026-10-12 | `companion/TalkDecider.kt` に `TalkSituation`・`NearbySpot`・`TalkDecision`・`TalkDecider.decide` を作り、`maybeTalk()` の判定を順番も式も変えずに移した。`maybeTalk()` は状況を集める（`situation()`）・決める・実行する（`when (decision)`）だけになった。定数 `ANNOUNCE_RADIUS_M`・`STATIONARY_MINUTES`・`REST_MINUTES` は `TalkDecider` に移した（`WalkService.ANNOUNCE_RADIUS_M` は同じ値を指す）。決定は D-3 の 11 通り（名前は `NearFacility`・`Nothing`）。場面テスト `TalkDeciderTest` 18 件（図 2 の 8 つの判断の出る・出ない場面、ぶつかる場面、channels.md V-08）。わざと判定を変えた 3 通り（地点のきっかけを止める、切り替えの直後に施設を止める、とどまっている判定の境界をずらす）が、それぞれテストで落ちることを確かめた。結果: テストは app 93 → 111（+18）。`./gradlew test lint assembleRelease` が通る。エミュレータ: 散策の開始のあいさつ → 再訪のスポットの一言（「鶴岡八幡宮、4回目ですね」とルート）→ 途中でチャンネルを歴史探訪に切り替えたあいさつ → 時刻を 30 分進めてトイレの案内（「90m ほどのところにトイレがあります」）→ 終了。落ちない |
| 2 | 2026-10-12 | architecture.md 図 2 と「変更の入口」を `TalkDecider` を指すように直した。channels.md の V-08 の注記、TODO の表も |
| 3 | 2026-10-12 | 設計（D-10〜D-14）を開発者が承認 |
| 4 | 2026-10-12 | `platform/kit-companion`（JVM。`io.github.duwenji.yorisoi.kit.companion`）に `TalkGates`・`TalkRule`・`TalkEngine` を作った（テスト 5 件）。`TalkSituation` が `TalkGates<WalkGate>` として 4 つのゲートに答え、`TalkDecider` は 8 つのルールの並び（`TalkDecider.rules`）とエンジンになった。手順 1 の場面テスト 18 件は書き換えずに通った（テストのフォルダに差分なし）。ルールの並びと `safety` の宣言（天気・日の入り・施設）を固定するテストを 1 件足した。結果: テストは app 111 → 112、kit-companion 5。`./gradlew test lint assembleRelease` が通る。エミュレータ: 開始 → 再訪のスポットの一言（「5回目ですね」）→ 自然観察への切り替えのあいさつ → 時刻を 30 分進めてトイレの案内 → 終了。落ちない。**段階 2 はこれで終わり** |

### 戻し方

手順ごとに PR を分ける。手順 1 は判定を移すだけなので PR を戻せば元に戻る。手順 4 で問題が出たら、`TalkDecider` を手順 1 の形に戻せばよい（場面テストは両方で通る）。

## 影響範囲

| 観点 | 影響 |
|---|---|
| 散策者 | なし |
| チャンネル提供者・素材提供者 | なし（形式を変えない） |
| 開発 | 話す条件を変えるときの入口が `WalkService.maybeTalk()` から `TalkDecider`（段 B の後はルールの並び）に変わる。architecture.md の「変更の入口」を直す |
| 文書 | architecture.md 図 2・「変更の入口」、yorisoi-tutorial 第 3 部 3-4（`maybeTalk()` の判断）。tutorial は段階 2 の後にまとめて直す（2026-10-12 の決定） |

## 未決事項・リスク

| No. | 内容 | 対応 |
|---|---|---|
| 1 | `TalkSituation` が大きくなる（今の判定が読む値は 20 余り） | 段 A では 1 つの値のクラスにまとめる。段 B で、ルールごとに要る値が分かれれば分ける |
| 2 | 状況を集める処理（`WalkService` の側）にテストがない | 集める処理は今の式の写し（`s.restMinutes(now)` などをそのまま呼ぶ）にとどめ、判定の式を集める側に残さない。エミュレータで確かめる |
| 3 | `kit-companion` を JVM にできるか（`TalkLevel` の間隔や `Thresholds` はアプリの値） | 手順 3 で決める。エンジンは値を持たず、アプリのルールが状況から値を読む形なら JVM で足りる見込み |

リスク:
- 判定と実行を分けるとき、今は判定の途中で更新している値（例: スポットの案内で `lastSpotTalkAt` を更新してから話す）を、決定の後に更新する形になる。同じ呼び出しの中で次の判定は行わないので結果は同じだが、場面テストとエミュレータで確かめる
- 場面テストで書き漏らした条件は、段 B の書き換えで変わっても気づけない。D-6 の「出ない場面」を、`maybeTalk()` の `if` の条件ごとに 1 つ以上入れる

## 関連ドキュメント・参照リンク

- [ADR-003](../adr/ADR-003-companion-app-platform.md): 付き添いアプリのエコシステム（E-7・E-23、T-2・T-3）
- [RFC-002](RFC-002-platform-stage1.md): 段階 1（共通部分の切り出し）
- [architecture.md](../architecture.md) 図 2: 散策モードの話しかけるまでの流れ

## 変更履歴

| 日付 | 版 | 変更内容 | 変更者 |
|---|---|---|---|
| 2026-10-12 | 0.1 | 草案 | Claude |
| 2026-10-12 | 1.0 | 承認 | Claude（承認: 開発者） |
| 2026-10-12 | 1.1 | 手順 1・2 を実施（#44）。手順 3 の設計（D-10〜D-14: ゲート・ルール・エンジン・safety の意味・JVM のモジュール）と、サイクリングで確かめた例を追加 | Claude |
| 2026-10-12 | 1.1 | 承認（手順 3 の設計レビュー） | Claude（承認: 開発者） |
| 2026-10-12 | 1.2 | 手順 4 を実施。実施の記録 | Claude |
