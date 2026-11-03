# Reference Architecture Alignment Validation Protocol

This document describes the procedure used to collect human judgments for validating component-level mappings between AADL models and reference architecture (RA) components.

## Objective

The goal of this study is to assess the correctness of mappings between AADL components and RA components, which are used as ground truth for alignment, reuse, and conformance analysis.

## Scope

The validation focuses on the **SMART_HOME** cluster:

- 25 AADL models  
- IoT Reference Architecture  
- 187 component-level mappings  

## Participants

The study involves **12 participants** with background in software architecture and model-based system design (graduate students and researchers).

## Materials

Each mapping is presented in a structured spreadsheet with:

- `AADL_model`: model identifier  
- `component_name`: AADL component  
- `ra_component`: assigned RA component  
- `graphical_illustration`: visual mapping  
- `validation_status`: {Correct, Incorrect, Uncertain}  
- `correction_suggestion`: optional comments  

Participants are also provided with:

- RA component descriptions  
- RA visualization  
- instructions defining evaluation criteria  

## Procedure

Participants independently evaluate assigned mappings:

1. Inspect the AADL component and proposed RA component  
2. Consult supporting materials if needed  
3. Assign one label:
   - **Correct**: mapping is semantically consistent  
   - **Incorrect**: mapping is wrong  
   - **Uncertain**: insufficient information  

Participants are instructed to focus on **functional responsibility**, not naming similarity.

## Workload Distribution

- 187 mappings are divided into **4 batches**  
- Each participant evaluates **one batch (~40–50 mappings)**  
- Each mapping receives **3 independent evaluations**  

## Aggregation

Final labels are computed using **majority voting**.

Mappings with:
- disagreement, or  
- majority *Uncertain*  

are **manually reviewed** by the authors.

## Quality Assurance

To improve reliability:

- multiple independent judgments per mapping  
- standardized instructions  
- shared RA definitions  
- visual mapping support  
- manual inspection of ambiguous cases  

## Outcome

The result is a **validated set of component-level mappings** between AADL models and the IoT Reference Architecture.

This dataset can be used for:

- benchmarking alignment techniques  
- studying architectural conformance  
- analyzing architectural reuse  

## Replication

To replicate this study:

- use independent evaluators  
- provide RA descriptions and visual mappings  
- apply the same labeling scheme  
- aggregate using majority voting  
- manually inspect uncertain cases  

## Contact

For questions, please contact:

Thi Dinh Tran — thidinh.tran@gssi.it