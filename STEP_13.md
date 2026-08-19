# Step 13 — Agent Tool Loop

- Added bounded multi-round tool execution to the main ChatViewModel flow.
- Added duplicate tool-call signature detection to prevent infinite repeat loops.
- Tool results are capped before being fed back to the model.
- Added an explicit tool transcript so sequential calls retain prior call/result context.
- Agent Skills now supports bounded multi-round tool calls instead of executing only the first call.
- Tool JSON parser now validates name format, JSON size, argument count and argument size.
