package mihon.domain.library.model.search

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class LibrarySearchParserTest {

    @Test
    fun `empty query parses to an empty and node`() {
        QueryNode.from("") shouldBe AndNode(emptyList())
    }

    @Test
    fun `plain terms become general nodes`() {
        QueryNode.from("reading") shouldBe GeneralQueryNode("reading", negated = false)
    }

    @Test
    fun `space separated terms are anded`() {
        QueryNode.from("foo bar") shouldBe AndNode(
            listOf(
                GeneralQueryNode("foo", negated = false),
                GeneralQueryNode("bar", negated = false),
            ),
        )
    }

    @Test
    fun `double pipe is an or`() {
        QueryNode.from("foo || bar") shouldBe OrNode(
            listOf(
                GeneralQueryNode("foo", negated = false),
                GeneralQueryNode("bar", negated = false),
            ),
        )
    }

    @Test
    fun `leading minus negates the term`() {
        QueryNode.from("-reading") shouldBe GeneralQueryNode("reading", negated = true)
    }

    @Test
    fun `known field is parsed as a field node`() {
        QueryNode.from("title:foo") shouldBe FieldQueryNode(MangaField.TITLE, "foo", negated = false)
    }

    @Test
    fun `unknown field falls back to a general node`() {
        QueryNode.from("unknown:foo") shouldBe GeneralQueryNode("unknown:foo", negated = false)
    }

    @Test
    fun `comparison field is parsed with comparator`() {
        QueryNode.from("unread>=5") shouldBe
            ComparisonQueryNode(ComparisonField.UNREAD, "5", Comparator.GTE, negated = false)
    }

    @Test
    fun `parenthesised or nested in an and with negation`() {
        val node = QueryNode.from("(foo || bar) -baz")
        node shouldBe AndNode(
            listOf(
                OrNode(
                    listOf(
                        GeneralQueryNode("foo", negated = false),
                        GeneralQueryNode("bar", negated = false),
                    ),
                ),
                // A negated bare term folds the negation into the general node ...
                GeneralQueryNode("baz", negated = true),
            ),
        )
    }

    @Test
    fun `negated parenthesised group becomes a not node`() {
        // ... whereas a negated group is wrapped in a NotNode
        QueryNode.from("-(foo || bar)") shouldBe NotNode(
            OrNode(
                listOf(
                    GeneralQueryNode("foo", negated = false),
                    GeneralQueryNode("bar", negated = false),
                ),
            ),
        )
    }

    @Test
    fun `field aliases resolve to the same field`() {
        QueryNode.from("lang:en") shouldBe FieldQueryNode(MangaField.LANGUAGE, "en", negated = false)
        QueryNode.from("tag:action") shouldBe FieldQueryNode(MangaField.GENRE, "action", negated = false)
    }

    @Test
    fun `quoted values keep separators`() {
        val node = QueryNode.from("title:\"foo bar\"")
        node.shouldBeInstanceOf<FieldQueryNode>()
        node.value shouldBe "foo bar"
    }
}
