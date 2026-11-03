## Annotation Guidelines for Model Clustering

### 1 Task Description

Annotate each AADL model with two labels:

* **Application domain** — the system context
* **Specific problem** — the system objective


### 2 Label Definitions

**Application Domain**
Represents the broad context in which the system is deployed.
Annotators should determine where the system is intended to operate.

**Specific Problem**
Represents the primary functional objective of the system.
Annotators should determine what the system is designed to achieve.


### 3 Annotation Procedure

For each model:

1. Inspect:

   * architectural structure (components, connections, hierarchy)
   * available documentation

2. Assign a **domain label** based on application context

3. Assign a **problem label** based on system functionality


### 4 Annotation Principles

* **Functional consistency**
  Group models that share the same objective and similar architectural structure

* **Independence**
  Domain and problem labels must be assigned independently

* **Consistency**
  Apply labeling decisions consistently across all models


### 5 Handling Ambiguity

When the correct label is unclear:

* prioritize the **primary functionality** of the system
* select the **most plausible interpretation**
* mark the case for later discussion if needed


### 6 Label Creation Policy

**Application Domain**
Introduce a new domain only if it is clearly distinct and consistently observed.

**Specific Problem**
Create a new cluster when multiple models share the same objective.
Otherwise, assign a fallback label (e.g., `OTHERS`).


### 7 Common Pitfalls

* Do not rely solely on naming
* Avoid grouping based on superficial similarity
* Avoid excessive fragmentation of clusters

All decisions must be based on functional interpretation.


### 8 Output Format

Each model must include:

* `domain_label`
* `problem_label`

Optional: short note for ambiguous cases.


### 9 General Instructions

* Perform annotations independently
* Do not discuss labels during the annotation phase
* Prefer conservative judgments when uncertain