#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
重做桌面小组件布局（v4.0.2 第三轮 · 行数自适应）。

用户实测的问题：
  「在桌面上占用两排图标的格子时，它实际能展示两行取件码；
    而占用 3 排格子时，它能展示 4 行，下面一堆是空着的。」

根因有两层：
  1. 行数算少了：rowsFor 里减掉了「查看全部」的 18dp（这行已被去掉）
     **又减了一个 -1 的保守余量**，两行误差叠加，少显示一行；
  2. 行高写死 40dp：即使算对了行数，放不下的整行 GONE 之后，
     剩余空间就是一块死白——这就是「下面一堆空着」。

改法：让【行高自适应】。
  · 每个数据行改成 layout_height="0dp" + layout_weight="1"，
    列表容器也用 weight 撑满剩余高度；
  · 行数仍由代码按「每行至少 40dp」算出（保证文字不被裁），
    但由于行是按权重均分的，实际行高 = 可用高度 / 行数，
    必然【填满整个组件】，且落在 40–79dp 这个可读区间内。

举例（可用高度 218dp，3 排格子）：
  · 旧：218 - 26 - 18 - 24 = 150 → 150/40 = 3 → 再 -1 = 2 行 → 底部空 70dp
  · 新：218 - 24 - 28 = 166 → 166/40 = 4 行 → 行高 41.5dp → 刚好填满

同时：
  · 去掉底部「查看全部」——点顶部标题跳转即可（用户明确要求，重复入口没必要）；
  · 标题字号 16sp → 18sp，并给标题行一个确定的最小高度，
    解决用户反馈的「标题下面好像被截取」；
  · 放不下的整行一律 GONE（不截半行）——半行会被误读成完整内容。
"""

import io
import os

N = 12          # 布局允许的最大行数
ROW_MIN_DP = 40  # 单行最小可读高度（代码侧算行数时也用这个值）

HEAD = '''<?xml version="1.0" encoding="utf-8"?>
<!--
  桌面小组件布局（v4.0.2 第三轮重做）。

  核心变化：行高改为【按权重自适应】。
  · 每行 layout_height="0dp" + layout_weight="1"，列表容器也用 weight 撑满，
    于是「行数 × 行高」恒等于可用高度——组件里有多少空间就填多少，
    不会再出现「下面一堆空着」。
  · 行数由代码按每行至少 40dp 算出，保证文字永远不会被裁；
    实际行高落在 40–79dp 之间，既填满又可读。
  · 放不下的整行 GONE，不做半行截断——半行会被误读成完整的一条。

  另外：
  · 去掉底部「查看全部」：点顶部标题即可跳转，两处入口重复反而让人犹豫；
  · 标题 18sp 并给标题行确定高度，解决「标题下沿被截」。
  · 注意：Android 桌面在 App 更新后【不会重建】已有小组件的视图树，
    布局结构变了必须移除旧组件再重新添加（设置页里有「重新添加小组件」入口）。
-->
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/widget_root_v2"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@drawable/widget_bg"
    android:orientation="vertical"
    android:padding="12dp">

    <!-- 标题行：标题可点（跳转取件记录页），右侧显示件数 -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:minHeight="28dp"
        android:gravity="center_vertical"
        android:orientation="horizontal">

        <TextView
            android:id="@+id/widget_title"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:ellipsize="end"
            android:maxLines="1"
            android:text="待取件"
            android:textColor="#FFFFFFFF"
            android:textSize="18sp"
            android:textStyle="bold" />

        <TextView
            android:id="@+id/widget_count"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="8dp"
            android:textColor="#B3FFFFFF"
            android:textSize="12sp" />
    </LinearLayout>

    <!-- 列表：weight 撑满剩余高度，行按权重均分 -->
    <LinearLayout
        android:id="@+id/widget_list"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:orientation="vertical">

        <TextView
            android:id="@+id/widget_empty"
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_weight="1"
            android:gravity="center"
            android:text="暂无待取件\\n收到短信后会自动出现"
            android:textColor="#B3FFFFFF"
            android:textSize="12sp"
            android:visibility="gone" />
'''

ROW = '''
        <LinearLayout
            android:id="@+id/widget_row_{i}"
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_weight="1"
            android:gravity="center_vertical"
            android:orientation="horizontal"{vis}>

            <TextView
                android:id="@+id/widget_code_{i}"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:fontFamily="monospace"
                android:textColor="#FFFFFFFF"
                android:textSize="17sp"
                android:textStyle="bold" />

            <TextView
                android:id="@+id/widget_place_{i}"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_marginStart="10dp"
                android:layout_weight="1"
                android:ellipsize="end"
                android:maxLines="1"
                android:textColor="#B3FFFFFF"
                android:textSize="11sp" />

            <TextView
                android:id="@+id/widget_done_{i}"
                android:layout_width="34dp"
                android:layout_height="34dp"
                android:background="@drawable/widget_done_bg"
                android:gravity="center"
                android:text="✓"
                android:textColor="#FFFFFFFF"
                android:textSize="16sp" />
        </LinearLayout>
'''

TAIL = '''    </LinearLayout>
</LinearLayout>
'''


def main():
    rows = ''
    for i in range(1, N + 1):
        # 前 5 行默认可见，其余 GONE（由 RemoteViews 按高度决定显几行）
        vis = '' if i <= 5 else '\n            android:visibility="gone"'
        rows += ROW.format(i=i, vis=vis)
    xml = HEAD + rows + TAIL
    out = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                       'app', 'res', 'layout', 'widget_pickup_v2.xml')
    io.open(out, 'w', encoding='utf-8', newline='\n').write(xml)
    print('小组件布局已重做：%d 行、行高自适应、去掉「查看全部」、标题 18sp' % N)


if __name__ == '__main__':
    main()
