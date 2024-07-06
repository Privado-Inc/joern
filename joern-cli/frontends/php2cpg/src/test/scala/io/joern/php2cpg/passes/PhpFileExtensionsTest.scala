package io.joern.php2cpg.passes

import io.joern.php2cpg.Config
import io.joern.php2cpg.parser.Domain.PhpOperators
import io.joern.php2cpg.testfixtures.PhpCode2CpgFixture
import io.shiftleft.semanticcpg.language.*

class PhpFileExtensionsTest extends PhpCode2CpgFixture() {

  "two files with two new extensions associated with PHP" should {

    val cpg = code(
      """
        |require 'vendor/autoload.php';
        |
        |$s3 = new S3Client([
        |    'version' => 'latest',
        |    'region'  => 'us-east-1',
        |    'credentials' => [
        |        'key'    => 'YOUR_AWS_ACCESS_KEY_ID',
        |        'secret' => 'YOUR_AWS_SECRET_ACCESS_KEY',
        |    ]
        |]);
        |""".stripMargin,
      "sample.php"
    )
      .moreCode(
        """<?php
          |require 'vendor/autoload.php';
          |
          |$s3 = new S3Client([
          |    'version' => 'latest',
          |    'region'  => 'us-east-1',
          |    'credentials' => [
          |        'key'    => 'YOUR_AWS_ACCESS_KEY_ID',
          |        'secret' => 'YOUR_AWS_SECRET_ACCESS_KEY',
          |    ]
          |]);
          |
          |""".stripMargin,
        "something.cls"
      )
      .withConfig(Config().withExtensions(Set(".php", ".cls")))

    "be scanned and present in cpg" in {
      cpg.file.name.l shouldBe List("sample.php", "something.cls", "<unknown>")
      cpg.file.name.l.size shouldBe 3
    }

  }

  "two files with only default (.php) extension associated with PHP" should {

    val cpg = code(
      """
        |require 'vendor/autoload.php';
        |
        |$s3 = new S3Client([
        |    'version' => 'latest',
        |    'region'  => 'us-east-1',
        |    'credentials' => [
        |        'key'    => 'YOUR_AWS_ACCESS_KEY_ID',
        |        'secret' => 'YOUR_AWS_SECRET_ACCESS_KEY',
        |    ]
        |]);
        |""".stripMargin,
      "sample.php"
    )
      .moreCode(
        """<?php
          |require 'vendor/autoload.php';
          |
          |$s3 = new S3Client([
          |    'version' => 'latest',
          |    'region'  => 'us-east-1',
          |    'credentials' => [
          |        'key'    => 'YOUR_AWS_ACCESS_KEY_ID',
          |        'secret' => 'YOUR_AWS_SECRET_ACCESS_KEY',
          |    ]
          |]);
          |
          |""".stripMargin,
        "something.cls"
      )

    "be scanned and one default extension present in cpg" in {
      cpg.file.name.l shouldBe List("sample.php", "<unknown>")
      cpg.file.name.l.size shouldBe 2
    }
  }

}
