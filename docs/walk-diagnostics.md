# 散歩の詳しい記録（開発者向け）

実際に歩いた散歩を後から振り返り、話しかけの頻度・しきい値、AI の文章、スポット検索（Overpass）の失敗を検討するための記録。開発者モードのときだけ取り、端末の外へは送らない。取り出すのは、開発者が記録の画面から書き出したときだけ。

## 使い方

1. 設定画面の一番下（「このアプリについて」）の「バージョン」を 7 回タップして、開発者モードにする
2. 散策する。開発者モードで始めた散歩だけが詳しく記録される（途中で開発者モードを切っても、その散歩は最後まで記録する）
3. 記録の画面（右上の時計アイコン）の書き出しボタン（↓）で、保存先を選んで JSON を保存する
4. パソコンに移して集計する

```sh
python tools/analyze_walks.py sanpoguide-walks-20261018-1530.json           # 散歩ごとのまとめ
python tools/analyze_walks.py sanpoguide-walks-20261018-1530.json --lines   # 発言を時刻・種類つきで全部
python tools/analyze_walks.py sanpoguide-walks-20261018-1530.json --ai      # AI に送った内容と答えを全部
python tools/analyze_walks.py sanpoguide-walks-20261018-1530.json --walk <id>
```

まとめで分かること:

| 見直したいこと | まとめに出るもの |
|---|---|
| 話しかけの頻度・しきい値 | 発言の回数（1km・1 時間あたり）、発言の間隔（最短・中央・最長）、種類ごとの数、判定の数、話し中で見送った数 |
| AI の文章 | AI を呼んだ回数・失敗・出力の確認（`OutputCheck`）で落ちた数・応答時間、解説の長さ、代わりの定型文になった理由 |
| Overpass の失敗 | サーバーごとの試行・成功・失敗（HTTP の状態か例外）と応答時間、すべてのサーバーで失敗した回数、天気の取得の失敗 |

発言の種類は、直前の判定（`decision`）から決める。最初の挨拶は `start`、チャンネルのきっかけの発言は `cue`、終わりのまとめは `summary`。

## 置き場所と量

- 端末の `files/diagnostics/<散歩の id>.jsonl`（1 行に 1 つの出来事）。散歩の id は散歩の記録（`walk_history.json`）の `id` と同じ
- 最新 30 件の散歩まで（platform kit-companion の `SessionRecorder.MAX_SESSIONS`）。古いものから消す
- 記録の画面で「記録をすべて削除」すると、詳しい記録も消える
- 散歩の記録と同じく、クラウドのバックアップにも端末間の移行にも含めない

## 書き出しの形式

```json
{
  "format": "sanpoguide-walk-export",
  "formatVersion": 1,
  "app": "1.0.0",
  "exportedAt": 1760770000000,
  "walks": [ { "id": …, "startedAt": …, "route": …, "visits": …, "diagnostics": [ … ] } ]
}
```

`walks` の各要素は散歩の記録の形式（`WalkJson`）そのままに、`diagnostics`（その散歩の出来事の配列。詳しい記録がなければ空）を足したもの。保存している散歩はすべて含める。項目の意味を変えるときは `formatVersion` を上げる。

## 出来事

どの出来事にも `t`（端末の時刻、エポックミリ秒）と `type` がある。

| `type` | いつ | 主な項目 |
|---|---|---|
| `walk_start` | 散歩の開始 | `app`・`station`・`level`（話しかけの頻度）・`aiConfigured`・`provider`・`model`・`shareLocationWithAi`・`dailyAiLimit`・`thresholds`（すべてのしきい値。既定値を含む）・`plan` |
| `location` | 位置の更新（10 秒・10m ごと） | `lat`・`lon`・`accuracyM`・`speed`・`distanceM`（ここまでの距離） |
| `decision` | `TalkDecider` が何かを話すと決めたとき（何もしないときは残さない） | `kind`（`spot`・`spot_cue`・`point_cue`・`weather_turn`・`greeting`・`sunset`・`near_facility`・`rest`・`milestone`・`checkpoint`）と、その対象（スポット・施設・天気など）。判定に使った値: `elapsedMs`・`walkedM`（ここまでの距離）・`restMinutes`・`sinceSpokeMs`・`sinceSpotTalkMs`・`spotsInReach`・`newSpotsInReach`・`level`・`openGates`（開いていたゲート） |
| `talk_busy` | 前の発言の生成中で、決めたことを見送ったとき | なし |
| `said` | 読み上げた発言 | `text`・`spot`・`credit`（AI なら「文: AI（モデル名）」）。終わりのまとめは `summary: true` |
| `cue` | チャンネルのきっかけを出したとき | `cue`（きっかけのキー）・`spot`・`items` |
| `ai` | AI を呼んだとき（散策中の画面からの解説も含む） | `kind`（`guide`: スポットの解説、`chat`: 話しかけ）・`model`・`ms`・`system`・`user`（送ったプロンプト）・`reply`・`error`・`violation`（出力の確認で見つかったもの） |
| `producer` | 素材提供者が作れず、次の候補（定型文など）に回ったとき | `message`（素材提供者と理由）・`error` |
| `overpass` | Overpass のサーバーへの 1 回の問い合わせ | `host`・`status`（HTTP の状態。応答がなければ null）・`error`（例外の名前）・`ms`（応答が始まるまで）。問い合わせの条件: `lat`・`lon`・`radiusM`（スポットを探した中心と半径。ベンチと自動販売機はもっと近くだけ）・`shopRadiusM`（店も探したときの半径。探さなければ null）・`queryChars`（問い合わせの長さ）。成功したときは `bytes`（応答の大きさ）・`elements`（応答の要素の数。JSON でなければ null） |
| `spot_search` | 表示するスポットが入れ替わったとき（300m 動くか、チャンネルの店の種類が変わったとき） | `lat`・`lon`（検索した位置）・`source`（スポットをどこから得たか。`network` はいま Overpass から、`cache` は端末に残した 7 日以内の答え、`stale` は 7 日より古い答え（問い合わせが終わるまで）、`fallback` は問い合わせの失敗か、失敗のあとの待ち時間の間に使った答え。詳しくは [kit-geo の README](https://github.com/duwenji/yorisoi-platform/blob/main/kit-geo/README.md#スポット検索のキャッシュ)）・`channel`（使っていたチャンネルの `key`・`id`・`name`・`trial`）・`shops`（そのチャンネルが探させた店の種類）・`spotCount`・`spots`（見つかったスポットを近い順に。`poiId`・`name`・`category`・`distanceM`。端末にあるチャンネルがそのスポットについて持っているものがあれば `channels`）・`facilityCount`・`facilities`（施設の種類ごとの数） |
| `spot_search_failed` | スポット検索がすべてのサーバーで失敗したとき | `error`・`source`（端末に残した近くの答えを代わりに出したときは `fallback`） |
| `weather` / `weather_failed` | 天気の取得（開始時と 15 分ごと） | `description`・`temperatureC`・`precipitationMm`・`code`・`sunsetAt`・`forecast`・`ms` / `error`・`ms` |
| `station` | 散歩の途中でチャンネルを切り替えたとき | `station`・`level` |
| `walk_end` | 散歩の終わり（終わりのまとめのあと） | なし |

`spot_search` の `channels` は、チャンネルごとに `key`・`name`・`cues`（そのスポットで出るきっかけ。`id` と `trigger`: `spot` は名前か ID で、`any` は種類で、`point` は範囲で当たったもの）・`resources`（そのスポットについての素材。`source`: `self` はチャンネル自身、`collection` は名指しした素材集、`selector` は基準で選んだ素材集。`alias`・`collection`・`version`・`producer`（素材提供者）・`id`・`type`）。きっかけは当たるかどうかだけで、`repeat` でもう出ないものも含む。素材を持たない組み込みのチャンネルは出てこない。

アプリが途中で止められた散歩には `walk_end` がない。書きかけで切れた行は読み飛ばす。

## 扱いの注意

- 書き出したファイルには、歩いた経路（自宅の近くを含む）と、AI に送ったプロンプトが入る。共有するときは気をつける
- API キーは記録しない
