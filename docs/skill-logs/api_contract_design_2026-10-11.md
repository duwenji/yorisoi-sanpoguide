# api-contract-design ログ

## 基本情報

| 項目 | 内容 |
|------|------|
| テーマ | 素材の制作元（ADR-002）に合わせた API-003 1.2・API-002 1.2 の改訂（ADR-002 T-2・T-3）。制作元 IF は API-004（承認済） |
| 記録日 | 2026-10-11 |
| 承認者 | 開発者（未承認） |
| モード | balance |

## 段階記録

- 実施内容: ADR-002・API-004 の承認（2026-10-11）を受け、開発者の「進んで」で改訂の草案を作成した
  - API-003 1.2: `collections`（素材集の宣言。ID だけで参照）、`present` の `source`・`sources`（候補の順番）、`narration` と `source` の `builtin:ai-guide` の併用の禁止、確かめる手順 9a、エラー `unresolved_reference`・`not_granted`・`collection_not_listed`、確認観点 V-17〜V-20。素材そのものの審査を素材集の審査に分けた
  - API-002 1.2: P-10〜P-14、`collections[]`・`revokedCollections[]`・`channels[].media`・`resourcesSize`、アプリの振る舞い（素材集の取得・更新・取り下げ・鍵の移し替え）、エラー `collection_rejected`・`producer_changed`、確認観点 V-19〜V-24
- 判断根拠: 実装済みのアプリ（ADR-001 T-5）と管理システムを壊さないよう、どちらも項目の追加にとどめ `format` を上げない。素材集の取り下げを `revoked[]` に混ぜると、古いアプリが同じ ID のチャンネルを止めるので別の配列にした
- 承認ステータス: 開発者の承認待ち（API-003 は 1.1 も承認待ち）
- 追記（同日）: ADR-002 1.1 に合わせ、API-003 1.2 に `selectors` と題材を問わないきっかけ（`"any": true`・`categories`）、V-21・V-22 を、API-002 1.2 に P-15（`collections[].tags`・`regions`・`grants`）、基準で選ぶ素材集の取得、V-25・V-26 を追加した。API-004 を 1.1 にした（基準で選ぶ制作元、タグの一覧、P-6）
- 承認（2026-10-11）: 開発者が API-003 1.1・1.2 と API-002 1.2 を承認した
