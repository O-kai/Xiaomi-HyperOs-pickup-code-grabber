# 取件码助手品牌图形

使用内置 imagegen 生成透明 RGBA 标识；源图为 pickup-logo-source.png，运行资源 pickup_brand.png 只缩小至 432px 并移除元数据，保留 alpha 通道。桌面与上岛均直接使用透明 bitmap。4.4.6 的透明 adaptive-icon 在实机桌面仍出现黑色底板，因此 4.4.7 改为直接提供位图图标；主题图标着色不再通过 adaptive-icon 提供。

最终提示词：

```text
Use case: logo-brand. Production logo for a parcel pickup assistant. One elegant compact closed parcel icon in a very simple flat geometric three-quarter view, three bold clean faces with rounded corners and clear negative-space seams, a short tape band over the top. Amber orange #CE8B35 body with a restrained warm brown #935D24 side face. Bold legible balanced silhouette, precise crisp vector-like contours, premium contemporary design. Single centered symbol occupies about 56% of a square canvas, equal transparent margins. TRUE TRANSPARENT RGBA BACKGROUND outside and between the shapes. No black background, no white background, no colored tile, no badge, no shadows, no texture, no gradients, no perspective scene, no letters, no digits, no checkmark, no watermark. Deliver the isolated parcel mark only, suitable for overlay on both light and dark UI.
```
