#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
精确上下文检测器 - 测试脚本
============================
验证 ContextDetector 能否正确识别各种语义上下文位置。
"""

import os
import sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from core.context_detector import ContextDetector


GRAMMAR_PATH = os.path.join(os.path.dirname(__file__), "grammars", "aadl_mvp.lark")


# 完整的前置文本（定义好所有类型，后面测试在这个基础上生成）
PREFIX = """package FlightControl is
public
  data SensorData
  end SensorData;

  data ControlCommand
  end ControlCommand;

  system Sensor
    features
      out_port : out data port SensorData;
  end Sensor;

  system Controller
    features
      in_port : in data port SensorData;
      out_port : out data port ControlCommand;
  end Controller;

  system Actuator
    features
      in_port : in data port ControlCommand;
  end Actuator;

  system FlightSys
  end FlightSys;

  system implementation FlightSys.impl
    subcomponents
      sensor1 : system Sensor;
      controller1 : system Controller;
      actuator1 : system Actuator;
    connections
"""


def run_tests():
    print("=" * 60)
    print("精确上下文检测器测试（基于 Earley 状态）")
    print("=" * 60)
    print()

    detector = ContextDetector(GRAMMAR_PATH)

    tests = [
        # (描述, 当前文本, 期望 context_type, 期望 info)
        (
            "在 data 类型定义中间（不在约束位置）",
            "package P is\npublic\n  data Se",
            "none",
            {},
        ),
        (
            "feature 的 data port 后面（刚开始输入类型）",
            PREFIX + "      c1 : ",
            "none",  # 刚开始输入连接名，不在约束位置
            {},
        ),
        (
            "connection 源端第一段（子组件名）",
            PREFIX + "      c1 : port sensor",
            "conn_end_first_segment",
            {"is_source": True, "segment_index": 0},
        ),
        (
            "connection 源端第二段（端口名，刚打点）",
            PREFIX + "      c1 : port sensor1.",
            "conn_end_second_segment",
            {"is_source": True, "segment_index": 1},
        ),
        (
            "connection 目标端第一段",
            PREFIX + "      c1 : port sensor1.out_port -> controller",
            "conn_end_first_segment",
            {"is_source": False, "segment_index": 0},
        ),
        (
            "connection 目标端第二段（刚打点）",
            PREFIX + "      c1 : port sensor1.out_port -> controller1.",
            "conn_end_second_segment",
            {"is_source": False, "segment_index": 1},
        ),
    ]

    passed = 0
    failed = 0

    for desc, text, expected_type, expected_info in tests:
        print(f"测试：{desc}")
        print(f"  期望: {expected_type}", end="")
        if expected_info:
            extra = {k: v for k, v in expected_info.items() if k != "constraint"}
            if extra:
                print(f" {extra}", end="")
        print()

        ctx, env = detector.detect(text)

        ok = True
        if ctx.context_type != expected_type:
            print(f"  ❌ 失败：实际是 {ctx.context_type}")
            ok = False
        elif expected_info:
            for key, val in expected_info.items():
                if ctx.info.get(key) != val and ctx.is_source != val:
                    # is_source 既在 info 里也在字段里，都检查一下
                    if key == "is_source" and ctx.is_source == val:
                        continue
                    print(f"  ❌ 失败：{key} 期望 {val}，实际 {ctx.info.get(key) if key in ctx.info else ctx.is_source if key=='is_source' else 'N/A'}")
                    ok = False

        if ok:
            print(f"  ✅ 通过 (context={ctx.context_type}, info={ctx.info})")
            passed += 1
        else:
            failed += 1
        print()

    print("=" * 60)
    print(f"结果：{passed} 通过，{failed} 失败")
    print("=" * 60)

    return failed == 0


if __name__ == "__main__":
    success = run_tests()
    sys.exit(0 if success else 1)
