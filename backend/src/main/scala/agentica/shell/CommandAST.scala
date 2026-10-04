package agentica.shell

/**
 *  Single parsed command in the Agentica DSL.
 *
 *  A canonical command string of the form `family_verb key=value key2="quoted value"`
 *  is parsed by [[Tokenizer]] into this structure.
 *
 *  @param family  Tool family name, e.g. `"files"`, `"memory"`, `"llm"`.
 *  @param verb    Action within the family, e.g. `"read"`, `"set"`, `"summarize"`.
 *  @param args    Key-value argument map; values are already unquoted and unescaped.
 */
case class Command(
    family: String,
    verb:   String,
    args:   Map[String, String]
)
{
    /** Canonical `family_verb` identifier, e.g. `"files_read"`. */
    def fullName: String = s"${family}_$verb"
}
