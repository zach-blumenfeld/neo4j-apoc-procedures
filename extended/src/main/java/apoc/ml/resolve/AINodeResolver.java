package apoc.ml.resolve;

import dev.langchain4j.service.UserMessage;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface AINodeResolver {
    @UserMessage("""
You are responsible for entity resolution, identifying whether two records refer to the same underlying entity, even if there are minor variations between them.
For each entity pair in the list provided below, analyze and determine whether the pair refers to the same entity or different entities. 
Consider variations in names, idfentifiers, dewcriotions, text, abbreviations, or misspellings, as real-world records often contain minor differences for the same entity.

# Instructions:
- Return SAME if the pair refers to the same entity, despite minor differences.
- Return DIFFERENT if they are not the same.
- Return an ordered list with your conclusions, preserving the same order as in the input.
    
# Entity Pairs Input
    {{it}}
    """)
    List<EntityLinkEnum> resolve(String entityPairString);
}
