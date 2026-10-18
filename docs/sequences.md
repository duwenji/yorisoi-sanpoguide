# 散策ガイドのシーケンス図

主な処理を、呼び出しの順に追う。部品の置き場所とつながりは[構造図](architecture.md)、ここでは「いつ・だれが・だれを呼ぶか」を示す。

| 場面 | 図 |
|---|---|
| アプリを開いたとき | [図 1 · 起動](#図-1--起動-sanpoapp-が共有部品を作る) |
| 散策を始めたとき | [図 2 · 散策の開始](#図-2--散策の開始-プランを保存してから-walkservice-を起こす) |
| 散策中、位置が変わるたび・30 秒ごと | [図 3 · 話しかけ](#図-3--話しかけ-位置の更新とタイマーから話すまで) |
| 散策中、スポットに近づいたとき | [図 4 · スポットの案内](#図-4--スポットの案内-きっかけか解説か再訪の一言) |
| チャンネルのきっかけが当たったとき | [図 5 · きっかけ](#図-5--きっかけ-チャンネルの素材を取り出して見せる) |
| 地図でスポットをタップしたとき | [図 6 · スポット解説](#図-6--スポット解説-解説と写真を別々に取る) |
| 第三者のチャンネルを使うとき | [図 7 · チャンネルの取り込み](#図-7--チャンネルの取り込み-確かめてから使う) |
| 散策中にチャンネルを切り替えたとき | [図 8 · 散策中の切り替え](#図-8--散策中の切り替え-一言を予約する) |
| 散策を終えたとき | [図 9 · 散策の終了](#図-9--散策の終了-記録してからまとめの一言) |

図はコードから手で書き起こしたもの。`WalkService`・`SanpoApp`・`ChannelSync` の呼び出し順を変えたときは、この文書も更新する。パスは `app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/` からの相対。

## 図 1 · 起動: SanpoApp が共有部品を作る

DI ライブラリは使わず、`SanpoApp.onCreate()` が部品を順に作る（構造図の図 1）。チャンネルのリストの取得は待たずに裏で進め、取れたものから `StationRepository` に入る。

```mermaid
sequenceDiagram
  autonumber
  participant OS as Android
  participant App as SanpoApp
  participant Set as SettingsRepository
  participant TP as ThirdPartyChannels
  participant Sync as ChannelSync
  participant List as チャンネル・リスト
  participant Repo as StationRepository
  participant Hist as HistoryStore

  OS->>App: onCreate()
  App->>Set: 設定を読む
  App->>TP: 作る（端末に保存済みのチャンネルを出す）
  App->>Repo: 組み込みのチャンネル + TP.stations + 設定
  App->>TP: refreshIfDue()
  TP-)Sync: refreshDue()（裏で、1 件ずつ）
  opt 24 時間たったリストだけ
    Sync->>List: 発見の文書・リスト
    List-->>Sync: 署名つきの文書
    Sync->>Sync: 署名・期限・順番を確かめ、取り下げを消す
  end
  Sync-->>TP: 使えるチャンネル
  TP-->>Repo: stations が変わる
  App->>App: GuideRepository・Speaker・MoodSource・WalkCompanion<br/>Producers・Narration・CueFinder・CuePresenter・CueShow を作る
  App-)Hist: load()
  App->>OS: 通知チャンネルを 4 つ登録
```

- `StationRepository.current` は、設定の `stationId` を一覧から探したもの。見つからなければ標準になる。端末に保存済みの第三者のチャンネルも裏で読むため、読み終わるまでは標準で、読み終わると選んでいたチャンネルに替わる。
- 散歩の外でチャンネルを選び直したときの `select` のきっかけは、`SanpoApp` が設定の `stationId` を見張って出す（図 5 の形）。散歩中は図 8 の流れになる。

## 図 2 · 散策の開始: プランを保存してから WalkService を起こす

メイン画面の「散策」ボタンは、まず散策プランの画面（`ui/WalkPlanScreen`）を開く。プランは任意で、テーマ・時間・距離・立ち寄るスポットを決められる。立ち寄り先の候補は、テーマに合う近くのスポット 3 件（`companion/PlanSuggestions`）。

```mermaid
sequenceDiagram
  autonumber
  actor W as 散策者
  participant Act as MainActivity
  participant Plan as ActiveWalkPlanStore
  participant WS as WalkService
  participant TP as ThirdPartyChannels
  participant Sess as WalkSession
  participant Feed as CompanionFeed
  participant Loc as 位置情報（FusedLocation）

  W->>Act: 「散策」→ プランを決めて開始（プランなしでも可）
  Act->>WS: WalkService.start(plan)
  WS->>Plan: save(plan)（active_walk_plan.json）
  WS->>WS: startForegroundService（プランを Intent に入れる）
  Note over WS: onStartCommand()
  WS->>WS: 常駐の通知「いってらっしゃい」
  WS->>TP: refreshIfDue()（散歩中は取りに行かない）
  WS->>Sess: WalkSession(plan)・startStation(current)
  opt 試用のチャンネル
    WS->>Sess: pendingGreeting = 試用の断り + あいさつ
  end
  WS->>Feed: startWalk(session)
  WS->>Loc: 10 秒・10m ごとの位置の更新
  WS->>WS: タイマー（30 秒ごと）・背景音・チャンネルの見張りを始める
```

- 保存に失敗すると、散策は始めずにプランの画面にエラーを出す。
- OS がサービスを再起動したとき（`Intent` が null）は、保存したプランを読み直して続ける。読めなければ「散策を再開できませんでした」と通知して止まる。
- プランは話す判断と文に効く。立ち寄るスポットは候補の中で先に選ばれ（`SpotChooser`。ただし地点のきっかけは追い越さない）、プランの要約は話しかけのプロンプトに入る（`WalkCompanion` の `plan`）。

## 図 3 · 話しかけ: 位置の更新とタイマーから話すまで

位置の更新とタイマーの両方が `maybeTalk()` を呼ぶ。`TalkDecider.decide()` は Android に依存しない純粋な関数で、ルールを上から評価して最初に当たった 1 件を返す（判定順は構造図の図 2）。`WalkService` はその決定を実行する。

```mermaid
sequenceDiagram
  autonumber
  participant Loc as 位置情報
  participant Tick as タイマー（30 秒）
  participant WS as WalkService
  participant Spots as SpotRepository
  participant Wx as WeatherClient
  participant TD as TalkDecider
  participant Nar as Narration
  participant Prod as Producers
  participant AI as AI サービス
  participant Out as Speaker・CompanionFeed・通知

  alt 位置の更新
    Loc->>WS: onLocation()
    WS->>WS: WalkSession に距離・経路を足す
    opt 最初の位置
      WS->>Wx: 天気・日の入り
      WS->>Nar: line(Start)
      WS->>Out: 開始の一言のあと、start のきっかけ（図 5）
    end
    WS->>Spots: onLocation()（300m 動いたら検索し直す）
  else タイマー
    Tick->>Wx: 15 分たっていれば取り直す
  end
  WS->>WS: maybeTalk()
  alt 読み上げ中
    WS-->>WS: 何もしない（次の判定で）
  else
    WS->>WS: situation()（60m 以内のスポット・きっかけ・経過・間隔）
    WS->>TD: decide(situation)
    TD-->>WS: TalkDecision
    Note over WS,TD: スポットは図 4、地点のきっかけは図 5<br/>ここでは一言で済む決定（天気・日の入り・施設・休憩・区切り）
    WS->>Nar: line(TalkEvent)
    Nar->>Prod: first([AI の話しかけ, 定型文])
    Prod->>AI: chat()（キーあり・上限内のとき）
    alt 返事が使える
      AI-->>Prod: 文
    else キーなし・上限・失敗・連絡先を含む
      Prod->>Prod: 定型文（fallback/）
    end
    Prod-->>WS: Material（文と作者）
    WS->>Out: speakLine()：カード・読み上げ・常駐の通知
    opt 区切り
      WS->>WS: saveProgress()（HistoryStore）
    end
  end
```

- `talk { }` は `Mutex` を `tryLock` するだけで、待たない。話している間に当たった決定は捨て、次の判定でもう一度決める。
- 雷雨の急変は、読み上げに加えて音つきの通知（`CHANNEL_ALERT`）も出す。イヤホンなしでも気づけるように。
- 区切りで話さないチャンネルでも、区切りの時点で記録は保存する（`TalkDecision.Checkpoint`）。
- AI の文には作者「文: AI（モデル名）」が付く（`material/creditLine`）。定型文には付かない。

## 図 4 · スポットの案内: きっかけか、解説か、再訪の一言

`TalkDecider` が `SpotCue`（チャンネルがきっかけを置いたスポット）か `Spot` を返したときの `talkAboutSpot()`。

```mermaid
sequenceDiagram
  autonumber
  participant WS as WalkService
  participant Cue as CuePresenter
  participant Hist as HistoryStore
  participant Nar as Narration
  participant GR as GuideRepository
  participant AI as AI サービス
  participant Feed as CompanionFeed
  participant Out as 通知・読み上げ

  WS->>WS: talkedAbout に足す
  opt きっかけがある（SpotCue）
    WS->>Cue: present(fire)（図 5）
    Cue-->>WS: 見せるもの（空のこともある）
  end
  WS->>Hist: visitsTo(poi)（今回の散歩を除く）
  alt きっかけで見せるものがある
    Note over WS: 解説は作らない
  else 初めてのスポット
    WS->>Nar: spotGuide(poi)
    Nar->>GR: guideFor(poi, channel, 根拠)
    alt キャッシュにある
      GR-->>Nar: 前に作った解説（数えない）
    else
      GR->>AI: generate(guide/system, guide/user)
      AI-->>GR: 解説
      GR->>GR: OutputCheck・キャッシュ
    end
    Nar-->>WS: 解説（失敗したら短い「近くに◯◯」）
  else 前の散歩で来た
    WS->>Nar: line(Revisit)
  end
  WS->>WS: visits に足す
  WS->>Feed: update・guide(poi)（地図のルートの対象）
  WS->>Hist: saveProgress()
  WS->>Out: スポットの通知（1 枠を使い回す）
  WS->>Out: きっかけを見せる / 文を読み上げる
```

- 解説のキャッシュの鍵は、AI サービス・モデル・チャンネル（版を含む）・解説の長さ・スポット・位置情報を送る設定・根拠の組み合わせ。図 6 のタップと共有する。
- きっかけの素材がどれも出せなかったときは、ふつうの解説に戻る。

## 図 5 · きっかけ: チャンネルの素材を取り出して見せる

きっかけは、スポット・地点・出来事（開始・終了・選んだとき）の 3 種類。どれも `CueFinder` が当たりを決め、`CuePresenter` が素材を取り出し、`CueShow` が見せる。

```mermaid
sequenceDiagram
  autonumber
  participant Caller as WalkService / SanpoApp
  participant Find as CueFinder
  participant Pres as CuePresenter
  participant P as 素材提供者<br/>組み込み・同梱・素材集
  participant Show as CueShow
  participant Out as 読み上げ・カード・通知

  Caller->>Find: spot() / point() / events()
  Find->>Find: repeat を見る（散歩ごと・1 日 1 回・1 回だけ）
  Find-->>Caller: CueFire
  Caller->>Pres: present(fire, channel, context)
  loop 見せ方ごと・項目ごと
    loop 候補の順に
      Pres->>P: produce(request)
      P-->>Pres: Ready / None / Unavailable
    end
    Note over Pres: 見せ方に合う最初の Ready を使う<br/>第 2・3 段（音声・映像）は飛ばす
  end
  Pres-->>Caller: 見せるもの
  Caller->>Find: record(fire)（cues.json）
  Caller->>Show: show(CueLayout)
  Show->>Out: 読み上げ・発言カード・通知
  Note over Out: 解説の画面と全画面の画像はタップしたときだけ開く
```

- 何も出せなかったきっかけも見せたことにする。判定のたびに試し直さないため。
- 素材集の資料を根拠として渡すのは、AI が作る素材提供者への依頼だけ（`groundedFor()`）。
- 素材は前もって取得したものだけを使う（図 7）。きっかけが当たってから取りには行かない。

## 図 6 · スポット解説: 解説と写真を別々に取る

地図でスポットをタップしたとき。散策中でなくても動く。

```mermaid
sequenceDiagram
  autonumber
  actor W as 散策者
  participant VM as MainViewModel
  participant Nar as Narration
  participant GR as GuideRepository
  participant AI as AI サービス
  participant Ph as SpotPhotos
  participant Wiki as Wikimedia
  participant UI as 解説パネル

  W->>VM: select(spot)
  VM->>UI: 読み込み中・ルートの対象にする
  VM->>Nar: peekSheetPhoto()（写真なし・Wi-Fi 待ちならすぐわかる）
  par 解説
    VM->>Nar: sheetGuide(spot)
    Nar->>GR: guideFor()
    GR->>AI: generate()（キャッシュになければ）
    AI-->>GR: 解説
    GR-->>Nar: 解説
    alt キーなし・上限
      Nar->>Nar: 定型文
    else 失敗
      Nar-->>VM: 失敗として出す（もう一度試せる）
    end
    Nar-->>VM: 解説と作者
    VM->>UI: 解説
  and 写真（手がかりがあるときだけ）
    VM->>Nar: sheetPhoto(spot)
    Nar->>Ph: photoFor()
    Ph->>Wiki: 写真
    Wiki-->>Ph: 画像・作者・ライセンス
    VM->>UI: 写真
  end
  W->>UI: 読み上げボタン
  UI->>VM: speak()
```

- 散策中と違い、AI の失敗はそのまま失敗として出す。散策者が画面を見ていて、やり直せるため。
- 結果が届いたときに別のスポットを選んでいたら、その結果は捨てる（`updateSelected`）。

## 図 7 · チャンネルの取り込み: 確かめてから使う

配信されているチャンネルの画面（`ui/ChannelsScreen`）で「使う」を押したとき。リストは前もって足してある（`addProvider`。リスト ID は散策者が別の経路で受け取ったもので、サーバーからは受け取らない）。

```mermaid
sequenceDiagram
  autonumber
  actor W as 散策者
  participant VM as ChannelsViewModel
  participant TP as ThirdPartyChannels
  participant Sync as ChannelSync
  participant Prov as チャンネル・リスト
  participant Val as StationValidator
  participant Store as ChannelStore
  participant Repo as StationRepository
  participant Set as SettingsRepository

  W->>VM: 使う
  VM->>TP: install(list, channel)
  TP->>Sync: install()（IO スレッドで 1 件ずつ）
  Sync->>Sync: 有効なリストか・形式・アプリで出せる範囲・必要な版
  Sync->>Prov: パッケージ（ZIP）
  Prov-->>Sync: バイト列
  Sync->>Sync: 大きさと SHA-256 がリストと一致するか
  Sync->>Val: checkArchive()（チャンネル提供者の署名・形式）
  Val-->>Sync: Ok / Rejected
  Sync->>Store: writePackage()
  Sync->>Prov: 素材集・外部の素材（前もって取得）
  Sync->>Store: 確かめて保存
  Sync-->>TP: 使えるチャンネル
  TP-->>Repo: stations が変わる
  VM->>W: AI の費用の目安（AiCost）
  alt 使う
    W->>VM: confirmUse()
    VM->>Set: stationId を保存
    Set-->>Repo: current が変わる
    Note over Repo: 散歩の外なら select のきっかけ（SanpoApp）<br/>散歩中なら図 8
  else やめる
    W->>VM: cancelUse()
    VM->>TP: uninstall()（パッケージも消す）
  end
```

- 確かめに通らなければ保存せず、理由を画面に出す（`ChannelSyncException`）。前の版があれば、新しい版が通るまで前の版を使う。
- チャンネル提供者が変わった新しい版は、リストに移し替えの裏付け（`publisherChange`）があるときだけ受け入れる。
- 散策者が「使う」を選ぶまで、そのチャンネルで AI は呼ばない。
- 試用チケット（開発者モード）も同じ確かめ方で、選ぶとすぐ使うチャンネルになる。

## 図 8 · 散策中の切り替え: 一言を予約する

`WalkService` は `StationRepository.current` を見張る。チャンネルが変わったら記録に区間を足し、新しいチャンネルの一言を予約して、すぐ `maybeTalk()` を呼ぶ。

```mermaid
sequenceDiagram
  autonumber
  actor W as 散策者
  participant Pick as StationPicker
  participant Set as SettingsRepository
  participant Repo as StationRepository
  participant WS as WalkService
  participant Sess as WalkSession
  participant TD as TalkDecider
  participant Out as 読み上げ・カード

  W->>Pick: チャンネルを選ぶ
  Pick->>Set: stationId を保存
  Set-->>Repo: current が変わる
  Repo-->>WS: watchStations()
  alt 同じチャンネルの設定を変えただけ
    WS-->>WS: 切り替えとみなさない
  else
    WS->>Sess: switchStation(key, あいさつ)・pendingSelect
    WS->>WS: maybeTalk()
    WS->>TD: decide()
    TD-->>WS: Greeting（天気の急変だけが先）
    WS->>Out: あいさつ（AI なし）
    WS->>Out: select のきっかけ（図 5）
  end
  Note over WS,TD: このあと 1 分はスポット案内と休憩・区切りを控える
```

- 切り替えでスポットの検索の種類（`searchedKinds`）が変わるときは、`SanpoApp` がスポットを検索し直す。
- 背景音は `current` と雰囲気を見て、選び直す（`startAmbient()`）。

## 図 9 · 散策の終了: 記録してから、まとめの一言

通知の「終了」かメイン画面のボタンで `WalkService` が止まる。まとめの一言とその後の処理は、サービスが止まっても続くように `SanpoApp.appScope` で動かす。

```mermaid
sequenceDiagram
  autonumber
  actor W as 散策者
  participant WS as WalkService
  participant Feed as CompanionFeed
  participant Plan as ActiveWalkPlanStore
  participant Scope as SanpoApp.appScope
  participant Hist as HistoryStore
  participant Nar as Narration
  participant Out as 読み上げ・カード

  W->>WS: 終了（通知 / メイン画面）
  Note over WS: onDestroy()
  WS->>WS: 位置の更新と背景音を止める
  WS->>Feed: endWalk()（歩いた道とルートを消す）
  WS->>Plan: clear()
  alt 1 分未満でスポットもない
    WS-->>WS: 記録しない
  else
    WS-)Scope: launch
    Scope->>Hist: save(record)
    opt チャンネルが終了で話す
      Scope->>Nar: line(Finish)
      Nar-->>Scope: まとめの一言
      Scope->>Out: カード・読み上げ
    end
    Scope->>Out: finish のきっかけ（図 5）
  end
```

- 終わったあとに生成が終わった文は、`speakLine()` が捨てる（常駐の通知を出し直さないため）。まとめの一言だけは、この図のとおり `appScope` から出す。
