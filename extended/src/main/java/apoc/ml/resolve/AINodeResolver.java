package apoc.ml.resolve;

import dev.langchain4j.service.UserMessage;

import java.util.Collection;
import java.util.List;
import java.util.Map;
//TODO: Allow user to provide a custom prompt...this is very data specific to get any quality
public interface AINodeResolver {
    @UserMessage("""
You are responsible for entity resolution, identifying whether two records are the same underlying entity, even if there are minor variations between them.

# Key Guidelines:
- Entities should only be considered SAME if they represent the exact same thing, concept, or entity.
- If two entities are merely related, complementary, or part of a larger concept (e.g., symptoms of a disease or characteristics of an entity), they should be treated as DIFFERENT.
- Ensure to look at all properties of each entity when determining this.
- Consider factors like identity, core meaning, and context—entities must be identical, not just share similarities.
- It is often the case that SAME entities will have different name (such as abreviations). Be sure to look at other properties like description to help make determination if avaiable.

# Instructions:
- Return SAME if both entities are the same core concept or object.
- Return DIFFERENT if they are distinct concepts, even if they are related.
- Return an ordered list with your conclusions, preserving the same order as in the input.
  
# Entity Pairs Input
    {{it}}
    """)
    List<EntityLinkEnum> resolve(String entityPairString);
}
