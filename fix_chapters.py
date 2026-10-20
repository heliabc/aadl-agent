#!/usr/bin/env python3
"""
修正 aadl.json 中所有条目的 category 字段，使其与 AS5506C AADL 2.2 标准的真实章节号一致。

参考 TOC（来自标准第 2-4 页）：
- Ch4: Components, Packages, and Annexes (4.1 AADL Specs, 4.2 Packages, 4.3 Component Types, 4.4 Component Impls)
- Ch5: Component Types (5.x 各种组件类型)
- Ch6: Component Implementations (6.x 各种组件实现)
- Ch7: Composite Components
- Ch8: Features
- Ch9: Connections
- Ch10: Flows
- Ch11: Properties
- Ch12: Modes and Mode Transitions
- Ch13: Operational System
- Ch14: Layered System Architectures
- Ch15: Lexical Elements
- Annex C: Syntax Summary
"""

import json
import re
import sys

# ── syntax_name → AS5506C 标准章节号 映射表 ──
# 格式: "syntax_name": "正确的 category"
CORRECT_CHAPTERS = {
    # ── 第 4 章：Components, Packages, and Annexes ──
    "aadl_specification":         "Chapter 4.1 - AADL Specifications",
    "package_declaration":        "Chapter 4.2 - Packages / Package Declaration",
    "package":                    "Chapter 4.2 - Packages / Package Declaration",
    "with_clause":                "Chapter 4.2 - Packages / With Clause",
    "public_private_section":     "Chapter 4.2 - Packages / Public and Private Sections",
    "component_type":             "Chapter 4.3 - Component Types",
    "component_implementation":   "Chapter 4.4 - Component Implementations",
    "component_category":         "Chapter 4.3 - Component Types / Component Categories",

    # ── 第 5 章：Component Types（软件类）──
    "thread_component_type":      "Chapter 5.2 - Thread Components / Component Type",
    "data_component_type":        "Chapter 5.3 - Data Components / Component Type",
    "subprogram_component_type":  "Chapter 5.4 - Subprogram Components / Component Type",
    "subprogram_group_type":      "Chapter 5.4 - Subprogram Components / Subprogram Group Type",
    "system_component_type":      "Chapter 5.5 - System Components / Component Type",
    "process_component_type":     "Chapter 5.6 - Process Components / Component Type",
    "abstract_component_type":    "Chapter 5.7 - Abstract Components / Component Type",

    # ── 第 5 章扩展：硬件组件类型（实际在第 6 章后，但为了分类清晰按标准顺序）──
    "processor_component_type":   "Chapter 6.2 - Processor Components / Component Type",
    "memory_component_type":      "Chapter 6.3 - Memory Components / Component Type",
    "bus_component_type":         "Chapter 6.4 - Bus Components / Component Type",
    "device_component_type":      "Chapter 6.5 - Device Components / Component Type",
    "virtual_processor_type":     "Chapter 6.6 - Virtual Processor Components / Component Type",
    "virtual_processor_component_type": "Chapter 6.6 - Virtual Processor Components / Component Type",
    "virtual_bus_type":           "Chapter 6.7 - Virtual Bus Components / Component Type",
    "virtual_bus_component_type": "Chapter 6.7 - Virtual Bus Components / Component Type",

    # ── 第 6 章：Component Implementations ──
    "component_type_extension":   "Chapter 5.3 - Component Type Extension",
    "component_implementation_extension": "Chapter 5.4 - Component Implementation Extension",

    # ── 第 7 章：Composite Components / Subcomponents ──
    "subcomponent_clause":        "Chapter 7.1 - Subcomponents",
    "subcomponent":               "Chapter 7.1 - Subcomponents / Subcomponent Declaration",
    "prototype_declaration":      "Chapter 7.2 - Prototypes",
    "prototype":                  "Chapter 7.2 - Prototypes",
    "component_prototype":        "Chapter 7.3 - Component Prototypes",
    "feature_prototype":          "Chapter 7.4 - Feature Prototypes",

    # ── 第 8 章：Features ──
    "feature_clause":             "Chapter 8.1 - Features / Feature Clause",
    "port_spec":                  "Chapter 8.2 - Features / Ports",
    "feature_group_spec":         "Chapter 8.3 - Features / Feature Groups",
    "feature_group_type":         "Chapter 8.8 - Feature Group Types",
    "feature_group_type_extension": "Chapter 8.9 - Feature Group Type Extension",
    "subprogram_access_spec":     "Chapter 8.4 - Features / Subprogram Access",
    "data_access_spec":           "Chapter 8.5 - Features / Data Access",
    "parameter_spec":             "Chapter 8.6 - Features / Parameters",
    "bus_access_spec":            "Chapter 8.7 - Features / Bus and Virtual Bus Access",
    "internal_feature":           "Chapter 8.10 - Internal Features",
    "processor_feature":          "Chapter 8.11 - Processor Features",

    # ── 第 9 章：Connections ──
    "connection_clause":          "Chapter 9.1 - Feature Connections",
    "connection":                 "Chapter 9.1 - Feature Connections",
    "feature_connection":         "Chapter 9.1 - Feature Connections",
    "port_connection":            "Chapter 9.2 - Port Connections",
    "feature_group_connection":   "Chapter 9.5 - Feature Group Connections",
    "parameter_connection":       "Chapter 9.3 - Parameter Connections",
    "access_connection":          "Chapter 9.4 - Access Connections",

    # ── 第 10 章：Flows ──
    "flow_specification":         "Chapter 10.1 - Flow Specifications",
    "flow_source_spec":           "Chapter 10.1 - Flow Specifications / Flow Source",
    "flow_sink_spec":             "Chapter 10.1 - Flow Specifications / Flow Sink",
    "flow_path_spec":             "Chapter 10.1 - Flow Specifications / Flow Path",
    "flow_implementation":        "Chapter 10.2 - Flow Implementations",
    "end_to_end_flow":            "Chapter 10.3 - End-To-End Flows",

    # ── 第 11 章：Properties ──
    "property_set":               "Chapter 11.1 - Property Sets",
    "property_type":              "Chapter 11.1.1 - Property Types",
    "property_type_declaration":  "Chapter 11.1.1 - Property Types",
    "property_definition":        "Chapter 11.1.2 - Property Definitions",
    "property_definition_declaration": "Chapter 11.1.2 - Property Definitions",
    "property_constant":          "Chapter 11.1.3 - Property Constants",
    "property_association":       "Chapter 11.3 - Property Associations",
    "contained_property_association": "Chapter 11.3 - Property Associations / Contained",
    "enumeration_type":           "Chapter 11.1.1 - Property Types / Enumeration Type",
    "range_type":                 "Chapter 11.1.1 - Property Types / Range Type",
    "record_type":                "Chapter 11.1.1 - Property Types / Record Type",
    "reference_type":             "Chapter 11.1.1 - Property Types / Reference Type",
    "classifier_type":            "Chapter 11.1.1 - Property Types / Classifier Type",
    "units_type":                 "Chapter 11.1.1 - Property Types / Units Type",
    "property_expression":        "Chapter 11.4 - Property Expressions",

    # ── 第 12 章：Modes and Mode Transitions ──
    "mode_declaration":           "Chapter 12.1 - Modes / Mode Declaration",
    "mode":                       "Chapter 12.1 - Modes / Mode Declaration",
    "modes_subclause":            "Chapter 12.1 - Modes / Modes Subclause",
    "mode_transition":            "Chapter 12.2 - Mode Transitions",
    "mode_transition_trigger":    "Chapter 12.2 - Mode Transitions / Transition Trigger",

    # ── 第 13 章：Operational System ──
    "subprogram_call_sequence":   "Chapter 13.4 - Subprogram Calls",
    "subprogram_call":            "Chapter 13.4 - Subprogram Calls / Subprogram Call",
    "called_subprogram":          "Chapter 13.4 - Subprogram Calls / Called Subprogram",

    # ── 第 14 章：Layered System Architectures（导入/别名等）──
    "import_declaration":         "Chapter 5.5 - Import Declarations",
    "alias_declaration":          "Chapter 5.6 - Alias Declarations",

    # ── Annex 子句（第 4 章后半）──
    "annex_subclause":            "Chapter 4.8 - Annex Subclauses",
    "annex_library":              "Chapter 4.9 - Annex Libraries",
    "dataset_annex_library":      "Annex F.1 - Data Sets / Dataset Annex Library",
    "dataset_annex_subclause":    "Annex F.1 - Data Sets / Dataset Annex Subclause",

    # ── 第 15 章：Lexical Elements ──
    "identifier":                 "Chapter 15.3 - Lexical Elements / Identifiers",
    "numeric_literal":            "Chapter 15.4 - Lexical Elements / Numeric Literals",
    "string_literal":             "Chapter 15.5 - Lexical Elements / String Literals",
    "comment":                    "Chapter 15.6 - Lexical Elements / Comments",
    "reserved_word":              "Chapter 15.7 - Lexical Elements / Reserved Words",
    "delimiter":                  "Chapter 15.2 - Lexical Elements / Delimiters",
    "unit_identifier":            "Chapter 15.3 - Lexical Elements / Unit Identifiers",
    "keywords":                   "Chapter 15.7 - Lexical Elements / Keywords (Reserved Words)",
    "delimiters_and_operators":   "Chapter 15.2 - Lexical Elements / Delimiters and Operators",

    # ── Annex C：Expression Language ──
    "expression":                 "Annex C.2 - Expression Language",
    "property_reference":         "Annex C.2 - Expression Language / Property References",
}


def main():
    json_path = r"c:\Users\heli0\Desktop\暑期实习\aadl生成\knowledge\aadl.json"

    with open(json_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    basics = data.get("basics", [])
    changes = []
    unmapped = []

    for i, entry in enumerate(basics):
        name = entry.get("syntax_name", "")
        old_cat = entry.get("category", "")
        new_cat = CORRECT_CHAPTERS.get(name)

        if new_cat is None:
            unmapped.append((name, old_cat))
            continue

        if old_cat != new_cat:
            changes.append((name, old_cat, new_cat))
            entry["category"] = new_cat

    # 写回文件
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)

    # 输出报告
    print("=" * 80)
    print(f"章节号修正报告（共 {len(basics)} 条）")
    print("=" * 80)
    print(f"\n✅ 修正 {len(changes)} 条：\n")
    for name, old, new in changes:
        print(f"  {name:40s}  {old:55s} → {new}")

    if unmapped:
        print(f"\n⚠️  未映射 {len(unmapped)} 条（需手动检查）：\n")
        for name, cat in unmapped:
            print(f"  {name:40s}  当前: {cat}")

    print(f"\n总计: {len(changes)} 条已修正, {len(unmapped)} 条未映射")


if __name__ == "__main__":
    main()
