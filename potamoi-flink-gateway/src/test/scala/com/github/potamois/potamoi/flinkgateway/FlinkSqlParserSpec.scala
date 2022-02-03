package com.github.potamois.potamoi.flinkgateway

import com.github.potamois.potamoi.flinkgateway.FlinkSqlParser.splitSqlStatement
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.language.postfixOps

class FlinkSqlParserSpec extends AnyWordSpec with Matchers {

  "FlinkSqlParser" when {

    "splitSqlStatement" should {
      "split normal sql content" in {
        val sql =
          """
            |  CREATE TABLE datagen_source (
            |    f_sequence INT,
            |    f_random INT,
            |    f_random_str STRING
            |  ) WITH (
            |    'connector' = 'datagen',
            |    'rows-per-second'= '25'
            |  );
            |
            |DESCRIBE datagen_source;
            |
            |show CATALOGS;
            |
            |explain SELECT * FROM datagen_source;
            |
            |""".stripMargin
        val extractedSqls = splitSqlStatement(sql)

        extractedSqls.size shouldBe 4
        extractedSqls shouldBe Seq(
          """CREATE TABLE datagen_source (
            |    f_sequence INT,
            |    f_random INT,
            |    f_random_str STRING
            |  ) WITH (
            |    'connector' = 'datagen',
            |    'rows-per-second'= '25'
            |  )""".stripMargin,
          "DESCRIBE datagen_source",
          "show CATALOGS",
          "explain SELECT * FROM datagen_source"
        )
      }

      "split empty sql content" in {
        splitSqlStatement(null) shouldBe Seq.empty
        splitSqlStatement("") shouldBe Seq.empty
        splitSqlStatement("   ") shouldBe Seq.empty
        splitSqlStatement("\n \n") shouldBe Seq.empty
        splitSqlStatement(";") shouldBe Seq.empty
        splitSqlStatement("  ;  ") shouldBe Seq.empty
        splitSqlStatement("  ;  ;  ") shouldBe Seq.empty
        splitSqlStatement("\n ; \n ; \n") shouldBe Seq.empty
      }

      "split sql content with single line comment using '//'" in {
        splitSqlStatement("// comment") shouldBe Seq.empty
        splitSqlStatement("// comment \n select * from table1") shouldBe Seq("select * from table1")
        splitSqlStatement("select * from table1 // comment ") shouldBe Seq("select * from table1")
        splitSqlStatement(
          """
            |// comment
            |   //comment 2
            |  CREATE TABLE datagen_source (
            |    f_sequence INT,
            |    f_random INT,  // comment 3
            |    f_random_str STRING // comment 4
            |  ) WITH (
            |  // comment 10
            |    'connector' = 'datagen',
            |    'rows-per-second'= '25'
            |  );
            |
            |DESCRIBE datagen_source ;//comment 5
            |
            |// comment 6; comment 7
            |show CATALOGS // comment 8;
            |
            |////comment9
            |""".stripMargin) shouldBe Seq(

          """CREATE TABLE datagen_source (
            |    f_sequence INT,
            |    f_random INT,
            |    f_random_str STRING
            |  ) WITH (
            |    'connector' = 'datagen',
            |    'rows-per-second'= '25'
            |  )""".stripMargin,
          "DESCRIBE datagen_source",
          "show CATALOGS")
      }

      "split sql content with single line comment using '--'" in {
        splitSqlStatement("-- comment") shouldBe Seq.empty
        splitSqlStatement("-- comment \n select * from table1") shouldBe Seq("select * from table1")
        splitSqlStatement("select * from table1 -- comment ") shouldBe Seq("select * from table1")
        splitSqlStatement(
          """
            |-- comment
            |   --comment 2
            |  CREATE TABLE datagen_source (
            |    f_sequence INT,
            |    f_random INT, -- comment 3
            |    f_random_str STRING  -- comment 4
            |  ) WITH (
            |  -- comment 10
            |    'connector' = 'datagen',
            |    'rows-per-second'= '25'
            |  );
            |
            |DESCRIBE datagen_source ;--comment 5
            |
            |-- comment 6; comment 7
            |show CATALOGS -- comment 8;
            |
            |------- comment9
            |""".stripMargin) shouldBe Seq(

          """CREATE TABLE datagen_source (
            |    f_sequence INT,
            |    f_random INT,
            |    f_random_str STRING
            |  ) WITH (
            |    'connector' = 'datagen',
            |    'rows-per-second'= '25'
            |  )""".stripMargin,
          "DESCRIBE datagen_source",
          "show CATALOGS")
      }

      "split sql content with multiple lines comment using '/* */'" in {
        splitSqlStatement("/* comment */") shouldBe Seq.empty
        splitSqlStatement("/* comment \n comment \n */") shouldBe Seq.empty
        splitSqlStatement("/* comment \n comment */ select * from table1") shouldBe Seq("select * from table1")
        splitSqlStatement("select * from table1 /* comment \n comment */ ") shouldBe Seq("select * from table1")

        splitSqlStatement(
          """
            |/* comment 1 */
            |/*comment2*/
            |CREATE TABLE datagen_source (
            |    f_sequence INT,
            |    f_random INT,  /* comment3 */
            |    f_random_str STRING
            |  ) WITH (
            |  /* comment 4
            |      comment 4 */
            |    'connector' = 'datagen',
            |    'rows-per-second'= '25'  /* comment 5
            |         comment 5
            |    */
            |  );
            |
            |/* comment 6
            |   comment 6
            |   comment 6 */
            |DESCRIBE datagen_source;
            |
            |show CATALOGS; /* comment 7
            |comment 7
            | */
            |
            |explain SELECT * FROM datagen_source /* comment 8 */;
            |/**/
            |""".stripMargin) shouldBe Seq(

          """CREATE TABLE datagen_source (
            |    f_sequence INT,
            |    f_random INT,
            |    f_random_str STRING
            |  ) WITH (
            |    'connector' = 'datagen',
            |    'rows-per-second'= '25'
            |  )""".stripMargin,
          "DESCRIBE datagen_source",
          "show CATALOGS",
          "explain SELECT * FROM datagen_source")
      }

      "split sql content with multiple lines comment using unclosed '/* */" in {
        splitSqlStatement(
          """
            |/* comment 1 */
            |DESCRIBE datagen_source;
            |  /* comment2
            |  show CATALOGS;
            |""".stripMargin) shouldBe Seq.empty
        splitSqlStatement(
          """
            |DESCRIBE datagen_source;
             comment2 */
            |  show CATALOGS;
            |""".stripMargin) shouldBe Seq.empty
      }

      "split sql content with comments of mixed use '//', '--' and '/**/'" in {
        splitSqlStatement(
          """ /* comment 1
            |  comment 1 */
            | -- comment 2
            |// comment 3
            |CREATE TABLE datagen_source (
            |    f_sequence INT, -- comment 4
            |    f_random INT,  // comment 5
            |    f_random_str STRING
            |  ) WITH (
            |  /* comment 5
            |      comment 5 */
            |    'connector' = 'datagen',
            |    'rows-per-second'= '25'
            |  );
            |
            |/* comment 6 */
            |DESCRIBE datagen_source;
            |
            |show CATALOGS; -- comment 7
            |
            |explain SELECT * FROM datagen_source // comment 8
            |
            |/* // comment9 -- comment10 */
            |-- comment 11 // comment 12
            | /* comment 13
            |   -- comment 14
            |     // comment 15
            |*/
            |""".stripMargin) shouldBe Seq(

          """CREATE TABLE datagen_source (
            |    f_sequence INT,
            |    f_random INT,
            |    f_random_str STRING
            |  ) WITH (
            |    'connector' = 'datagen',
            |    'rows-per-second'= '25'
            |  )""".stripMargin,
          "DESCRIBE datagen_source",
          "show CATALOGS",
          "explain SELECT * FROM datagen_source")
      }
    }

  }

}