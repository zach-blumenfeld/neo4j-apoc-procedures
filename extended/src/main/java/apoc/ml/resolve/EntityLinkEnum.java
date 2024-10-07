package apoc.ml.resolve;

import dev.langchain4j.model.output.structured.Description;

public enum EntityLinkEnum {
    @Description("The pair refers to the same entity (\"Duplicate\").")
    SAME,
    @Description("the pair refers to different entities (\"Not Duplicate\").")
    DIFFERENT;
}
