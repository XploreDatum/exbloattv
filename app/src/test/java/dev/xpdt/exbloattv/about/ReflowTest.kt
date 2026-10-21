package dev.xpdt.exbloattv.about

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class ReflowTest {
    @Test
    fun `joins hard-wrapped lines of a paragraph`() {
        val text = "Permission is hereby granted, free of charge, to any person obtaining a copy\nof this software and associated documentation files (the \"Software\"), to deal"
        assertThat(reflowLicence(text)).isEqualTo(
            "Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the \"Software\"), to deal",
        )
    }

    @Test
    fun `keeps paragraphs apart with one blank line`() {
        assertThat(reflowLicence("First paragraph ends here.\n\n\n\nSecond one.")).isEqualTo("First paragraph ends here.\n\nSecond one.")
    }

    @Test
    fun `list items and numbered clauses start their own line`() {
        val text = "provided that the following conditions are met:\n" +
            "    * Redistributions of source code must retain the above copyright\n" +
            "notice, this list of conditions and the following disclaimer.\n" +
            "    * Redistributions in binary form must reproduce the above\n" +
            "copyright notice.\n" +
            "1.1. \"Contributor\" means each individual or legal entity that\n" +
            "    creates, contributes to the creation of, or owns Covered Software."
        assertThat(reflowLicence(text)).isEqualTo(
            "provided that the following conditions are met:\n" +
                "* Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.\n" +
                "* Redistributions in binary form must reproduce the above copyright notice.\n" +
                "1.1. \"Contributor\" means each individual or legal entity that creates, contributes to the creation of, or owns Covered Software.",
        )
    }

    @Test
    fun `short title lines are not run together`() {
        val text = "                                 Apache License\n                           Version 2.0, January 2004\n                        http://www.apache.org/licenses/"
        assertThat(reflowLicence(text)).isEqualTo("Apache License\nVersion 2.0, January 2004\nhttp://www.apache.org/licenses/")
    }

    @Test
    fun `drops decorative rules and box borders`() {
        val text = "1. Definitions\n--------------\n\n" +
            "************************************************************************\n" +
            "*                                                                      *\n" +
            "*  6. Disclaimer of Warranty                                           *\n" +
            "*  -------------------------                                           *\n" +
            "*  Covered Software is provided under this License on an \"as is\"       *\n" +
            "************************************************************************"
        assertThat(reflowLicence(text)).isEqualTo("1. Definitions\n\n6. Disclaimer of Warranty\n\nCovered Software is provided under this License on an \"as is\"")
    }

    @Test
    fun `never loses a word of the real licence texts`() {
        val notices = Notices.parse(File("../THIRD_PARTY_NOTICES.md").readText())
        val texts = notices.texts.values + File("../LICENSE").readText()
        for (t in texts) {
            val decor = Regex("""[-=*_]{3,}|\*""")
            fun words(s: String) = s.replace(decor, " ").split(Regex("\\s+")).filter { it.isNotBlank() }
            assertThat(words(reflowLicence(t))).isEqualTo(words(t))
        }
    }
}
