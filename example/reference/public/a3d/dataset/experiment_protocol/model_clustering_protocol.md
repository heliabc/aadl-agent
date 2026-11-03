## Model Clustering Protocol

### 1 Objective

This protocol specifies the procedure for constructing a ground truth clustering of AADL models along two independent dimensions:

* **Application domain** — the system context
* **Specific problem** — the system-level functional objective

The protocol is designed to support reproducible and consistent annotation.


### 2 Clustering Criteria

**Application Domain**
Models are grouped according to their application context.
The domain taxonomy is derived incrementally from the dataset during annotation.

**Specific Problem**
Models are grouped based on the functional objective of the system.
Models should be assigned to the same cluster if they:

* address the same design objective
* exhibit similar high-level architectural structure

The problem definition is independent of the application domain.


### 3 Annotation Procedure

The annotation process follows an iterative, consensus-based workflow:

1. **Independent annotation**
   Each annotator independently inspects the models and assigns:

   * one domain label
   * one problem label

2. **Comparison**
   Annotators compare their assignments and identify disagreements.

3. **Discussion and resolution**
   Discrepancies are resolved through discussion.

4. **Refinement**
   Label definitions and grouping criteria are refined as needed.

5. **Consensus**
   Final labels are agreed upon collaboratively.

6. **Validation (optional)**
   Additional reviewers may inspect and validate ambiguous cases.


### 4 Quality Control

To ensure annotation reliability:

* annotations are performed independently
* disagreements are explicitly reviewed
* labeling criteria are refined iteratively
* ambiguous cases are resolved through consensus


### 5 Reproducibility

To reproduce this protocol:

* involve at least two independent annotators
* apply both domain and problem criteria
* follow the same iterative consensus process
* document and review ambiguous cases



