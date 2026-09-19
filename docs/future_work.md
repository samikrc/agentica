# Documentation
* Rewrite README similar to a few other agentic frameworks
  - https://github.com/langchain-ai/deepagents
  - https://github.com/nousresearch/hermes-agent

# Features
## Guardrails
* Don't depend on prompts for enforcing a behavior. Has to be enforced from a boundary that owns the resource. Need to think about this more. [1]
* Log tool execution separately, and count tool executions from those logs; not from the LLM transcript. [1]
* Is there a way to match the effort to the question? Consider 3 effort levels [1]: 
  - Simple search (vector + web) and diversity reranking (MMR)
  - Fixed multi-angle retrieval
  - Agentic search (rewrite query -> plan: split + route -> N X SME ReAct loops -> merge -> Grounding clamp (citations validated against store) -> reviewer (advisory) -> response)

  [open question] How to match the effort to the question?

### References
[1] https://x.com/ArvindBattula/status/2077472447717085572

## Feature Comparison
Following are codebases that should be compared for features:
- https://github.com/nousresearch/hermes-agent
- https://github.com/andrewyng/openworker