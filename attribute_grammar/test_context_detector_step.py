#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
逐步式上下文检测器验证
========================
模拟 LLM token 级生成过程，逐步追加文本，
验证 ContextDetector 在每个关键位置的判断是否正确。
"""

import os
import sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from core.context_detector import ContextDetector


GRAMMAR_PATH = os.path.join(os.path.dirname(__file__), "grammars", "aadl_mvp.lark")


def run_tests():
    print("=" * 60)
    print("逐步式上下文检测器验证")
    print("=" * 60)
    print()

    detector = ContextDetector(GRAMMAR_PATH)

    # 定义生成步骤：(追加的文本, 期望的 context_type, 期望 info, 描述)
    # 每一步在上一步的基础上追加文本
    steps = [
        # ---- 包定义 ----
        ("package ", "none", {}, "package 关键字后"),
        ("FlightControl", "none", {}, "包名"),
        (" is\npublic\n", "none", {}, "进入 public 区"),

        # ---- data 类型定义 ----
        ("  data ", "none", {}, "data 关键字后"),
        ("SensorData", "none", {}, "data 类型名"),
        ("\n  end SensorData;\n", "none", {}, "data 类型结束"),

        ("  data ", "none", {}, "第二个 data 关键字后"),
        ("ControlCommand", "none", {}, "第二个 data 类型名"),
        ("\n  end ControlCommand;\n", "none", {}, "第二个 data 类型结束"),

        # ---- system 类型 + feature ----
        ("  system ", "none", {}, "system 关键字后"),
        ("Sensor", "none", {}, "system 类型名"),
        ("\n    features\n", "none", {}, "进入 features 区"),
        ("      out_port : ", "none", {}, "feature 名后"),
        ("out", "none", {}, "方向 out 后"),
        (" data port ", "feature_data_type", {"constraint": "must_be_data_category"}, "data port 后（即将输入类型名）"),
        ("SensorData", "feature_data_type", {"constraint": "must_be_data_category"}, "正在输入类型名 SensorData"),
        (";\n", "none", {}, "feature 结束"),
        ("    end Sensor;\n", "none", {}, "system 类型结束"),

        # ---- system implementation + subcomponent ----
        ("  system implementation ", "none", {}, "system implementation 后"),
        ("FlightSys", "none", {}, "实现的类型名"),
        (".impl\n", "none", {}, "实现名后"),
        ("    subcomponents\n", "none", {}, "进入 subcomponents 区"),
        ("      sensor1 : ", "none", {}, "subcomponent 名后"),
        ("system ", "subcomponent_classifier", {"category": "system", "constraint": "must_match_subcomp_category"}, "subcomponent 类别 system 后（即将输入 classifier）"),
        ("Sensor", "subcomponent_classifier", {"category": "system", "constraint": "must_match_subcomp_category"}, "正在输入 classifier Sensor"),
        (";\n", "none", {}, "subcomponent 结束"),

        # ---- connections ----
        ("    connections\n", "none", {}, "进入 connections 区"),
        ("      c1 : port ", "conn_end_first_segment", {"is_source": True, "segment_index": 0}, "连接源端第一段前"),
        ("sensor", "conn_end_first_segment", {"is_source": True, "segment_index": 0}, "正在输入源端子组件名 sensor"),
        ("1.", "conn_end_second_segment", {"is_source": True, "segment_index": 1}, "源端第二段（端口名）"),
        ("out_port", "conn_end_second_segment", {"is_source": True, "segment_index": 1}, "正在输入源端口名"),
        (" -> ", "conn_end_first_segment", {"is_source": False, "segment_index": 0}, "箭头后（目标端第一段开始）"),
        ("controller", "conn_end_first_segment", {"is_source": False, "segment_index": 0}, "目标端第一段"),
        ("1.", "conn_end_second_segment", {"is_source": False, "segment_index": 1}, "目标端第二段（端口名）"),
        ("in_port", "conn_end_second_segment", {"is_source": False, "segment_index": 1}, "正在输入目标端口名"),
        (";\n", "none", {}, "连接结束"),
    ]

    current_text = ""
    passed = 0
    failed = 0

    for i, (append_text, expected_ctx, expected_info, desc) in enumerate(steps):
        current_text += append_text
        ctx, env = detector.detect(current_text)

        ok = True
        if ctx.context_type != expected_ctx:
            ok = False

        for key, val in expected_info.items():
            if ctx.info.get(key) != val:
                ok = False

        status = "✅" if ok else "❌"
        if ok:
            passed += 1
        else:
            failed += 1

        # 只打印失败的，或每 10 步打印一次进度
        if not ok or i % 5 == 0:
            append_repr = repr(append_text[:40])
            print(f"[{i:2d}] {status} {desc}")
            if not ok:
                print(f"     追加: {append_repr}")
                print(f"     期望: {expected_ctx} {expected_info}")
                print(f"     实际: {ctx.context_type} {ctx.info}")
                # 打印当前文本末尾
                print(f"     当前末尾: ...{repr(current_text[-60:])}")

    print()
    print("=" * 60)
    print(f"结果：{passed} 通过，{failed} 失败")
    print("=" * 60)

    return failed == 0


if __name__ == "__main__":
    success = run_tests()
    sys.exit(0 if success else 1)
