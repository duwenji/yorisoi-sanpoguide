{{!
  スポット解説の依頼（ユーザープロンプト）。
  変数:
    name        スポット名
    category    種別（神社・寺院・史跡など）
    distance_m  利用者からの距離（m。公園など広さのあるスポットは境界まで）。不明なら null
    inside      利用者がスポットの敷地内にいるなら true
    tags        OpenStreetMap のタグ（"key=value" のリスト。解説に役立つものだけ）
    coords      スポットの緯度経度（例: 35.32580, 139.55620）。設定で位置情報を AI に送らないときは null
    grounded    資料があるなら true
    grounds     このスポットについての資料（素材提供者の資料のうち、AI の根拠に使ってよいもの。yorisoi-platform/docs/material-producer-api.md「AI の根拠」）。
                各要素は source（素材提供者名）と text（本文。山かっこは全角にしてある）。ないときは null
}}
散策中の利用者の近くにあるスポットを案内してください。
名称: {{name}}
種別: {{category}}
{{#coords}}
位置（緯度, 経度）: {{coords}}
{{/coords}}
{{#inside}}
利用者の位置: このスポットの敷地内
{{/inside}}
{{^inside}}
{{#distance_m}}
利用者からの距離: 約{{distance_m}}m
{{/distance_m}}
{{/inside}}
OpenStreetMapのタグ:
{{#tags}}
- {{.}}
{{/tags}}
{{#grounded}}
資料（運営が審査した素材提供者の資料）:
{{/grounded}}
{{#grounds}}
<資料 出典="{{source}}">
{{text}}
</資料>
{{/grounds}}
