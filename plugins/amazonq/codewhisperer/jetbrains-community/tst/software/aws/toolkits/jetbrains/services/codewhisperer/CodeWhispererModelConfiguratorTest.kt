// Copyright 2023 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codewhisperer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.registerServiceInstance
import com.intellij.testFramework.replaceService
import com.intellij.util.xmlb.XmlSerializer
import migration.software.aws.toolkits.jetbrains.services.codewhisperer.customization.CodeWhispererModelConfigurator
import org.assertj.core.api.Assertions.assertThat
import org.jdom.output.XMLOutputter
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.stub
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.codewhispererruntime.CodeWhispererRuntimeClient
import software.amazon.awssdk.services.codewhispererruntime.model.Customization
import software.amazon.awssdk.services.codewhispererruntime.model.FeatureValue
import software.amazon.awssdk.services.codewhispererruntime.model.ListAvailableCustomizationsRequest
import software.amazon.awssdk.services.codewhispererruntime.model.ListAvailableCustomizationsResponse
import software.amazon.awssdk.services.ssooidc.SsoOidcClient
import software.aws.toolkits.jetbrains.core.MockClientManagerRule
import software.aws.toolkits.jetbrains.core.credentials.DefaultToolkitConnectionManager
import software.aws.toolkits.jetbrains.core.credentials.LegacyManagedBearerSsoConnection
import software.aws.toolkits.jetbrains.core.credentials.MockCredentialManagerRule
import software.aws.toolkits.jetbrains.core.credentials.ToolkitConnectionManager
import software.aws.toolkits.jetbrains.core.credentials.ToolkitConnectionManagerState
import software.aws.toolkits.jetbrains.core.credentials.pinning.QConnection
import software.aws.toolkits.jetbrains.core.credentials.sono.Q_SCOPES
import software.aws.toolkits.jetbrains.core.credentials.sono.SONO_REGION
import software.aws.toolkits.jetbrains.core.credentials.sono.SONO_URL
import software.aws.toolkits.jetbrains.core.credentials.sono.isSono
import software.aws.toolkits.jetbrains.core.region.MockRegionProviderRule
import software.aws.toolkits.jetbrains.services.amazonq.CodeWhispererFeatureConfigService
import software.aws.toolkits.jetbrains.services.amazonq.FeatureContext
import software.aws.toolkits.jetbrains.services.amazonq.profile.QRegionProfile
import software.aws.toolkits.jetbrains.services.amazonq.profile.QRegionProfileManager
import software.aws.toolkits.jetbrains.services.amazonq.profile.QRegionProfileSelectedListener
import software.aws.toolkits.jetbrains.services.codewhisperer.credentials.CodeWhispererClientAdaptor
import software.aws.toolkits.jetbrains.services.codewhisperer.customization.CodeWhispererCustomization
import software.aws.toolkits.jetbrains.services.codewhisperer.customization.CodeWhispererCustomizationState
import software.aws.toolkits.jetbrains.services.codewhisperer.customization.DefaultCodeWhispererModelConfigurator
import software.aws.toolkits.jetbrains.utils.xmlElement
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

class CodeWhispererModelConfiguratorTest {
    @JvmField
    @Rule
    val applicationRule = ApplicationRule()

    @JvmField
    @Rule
    val projectRule = ProjectRule()

    @JvmField
    @Rule
    val credManager = MockCredentialManagerRule()

    @JvmField
    @Rule
    val regionProvider = MockRegionProviderRule()

    @JvmField
    @Rule
    val disposableRule = DisposableRule()

    @JvmField
    @Rule
    val mockClientManager = MockClientManagerRule()

    private lateinit var sut: DefaultCodeWhispererModelConfigurator
    private lateinit var mockClient: CodeWhispererRuntimeClient
    private lateinit var abManager: CodeWhispererFeatureConfigService
    private lateinit var mockClintAdaptor: CodeWhispererClientAdaptor
    private lateinit var mockQRegionProfileManager: QRegionProfileManager

    @Before
    fun setup() {
        mockClientManager.create<SsoOidcClient>()
        mockClient = mockClientManager.create<CodeWhispererRuntimeClient>()
        regionProvider.addRegion(Region.US_EAST_1)
        regionProvider.addRegion(Region.US_EAST_2)

        sut = spy(CodeWhispererModelConfigurator.getInstance() as DefaultCodeWhispererModelConfigurator).also { spyInstance ->
            ApplicationManager.getApplication().replaceService(
                DefaultCodeWhispererModelConfigurator::class.java, spyInstance, disposableRule.disposable
            )
        }

        (ToolkitConnectionManager.getInstance(projectRule.project) as DefaultToolkitConnectionManager).loadState(ToolkitConnectionManagerState())
        mockClient.stub {
            onGeneric { listAvailableCustomizations(any<ListAvailableCustomizationsRequest>()) } doReturn
                ListAvailableCustomizationsResponse.builder()
                    .customizations(
                        listOf(
                            Customization.builder()
                                .arn("arn_1")
                                .name("name_1")
                                .description("descirption_1")
                                .build()
                        )
                    )
                    .build()
        }

        abManager = mock {
            on { getCustomizationFeature() }.thenReturn(null)
        }

        ApplicationManager.getApplication().replaceService(
            CodeWhispererFeatureConfigService::class.java,
            abManager,
            disposableRule.disposable
        )

        mockClintAdaptor = mock()
        projectRule.project.registerServiceInstance(CodeWhispererClientAdaptor::class.java, mockClintAdaptor)

        mockQRegionProfileManager = mock {
            on { listRegionProfiles(any()) }.thenReturn(listOf(QRegionProfile("fake_name", "fake_arn")))
        }
        ApplicationManager.getApplication().replaceService(QRegionProfileManager::class.java, mockQRegionProfileManager, disposableRule.disposable)
    }

    @Test
    fun `should not override customization arn if there is one under AB test and manual selection has been made`() {
        val ssoConn = spy(LegacyManagedBearerSsoConnection(region = "us-east-1", startUrl = "url 1", scopes = Q_SCOPES))
        ToolkitConnectionManager.getInstance(projectRule.project).switchConnection(ssoConn)

        sut.switchCustomization(projectRule.project, CodeWhispererCustomization("selectedCustomizationArn", "customization_1", "description_1"))
        assertThat(sut.activeCustomization(projectRule.project))
            .isEqualTo(CodeWhispererCustomization("selectedCustomizationArn", "customization_1", "description_1"))

        abManager.stub {
            on { getCustomizationFeature() }.thenReturn(FeatureContext("customizationArnOverride", "foo", FeatureValue.builder().stringValue("bar").build()))
        }
        assertThat(sut.activeCustomization(projectRule.project))
            .isEqualTo(CodeWhispererCustomization("selectedCustomizationArn", "customization_1", "description_1"))
    }

    @Test
    fun `should override customization arn if there is one under AB test and manual selection has not been made`() {
        val ssoConn = spy(LegacyManagedBearerSsoConnection(region = "us-east-1", startUrl = "url 1", scopes = Q_SCOPES))
        ToolkitConnectionManager.getInstance(projectRule.project).switchConnection(ssoConn)

        sut.switchCustomization(projectRule.project, CodeWhispererCustomization("selectedCustomizationArn", "customization_1", "description_1"))
        assertThat(sut.activeCustomization(projectRule.project))
            .isEqualTo(CodeWhispererCustomization("selectedCustomizationArn", "customization_1", "description_1"))
        sut.invalidateCustomization("selectedCustomizationArn")

        abManager.stub {
            on { getCustomizationFeature() }.thenReturn(
                FeatureContext("customizationArnOverride", "foo", FeatureValue.builder().stringValue("overrideArn").build())
            )
        }
        abManager.getCustomizationFeature()?.let { customization ->
            sut.switchCustomization(
                projectRule.project,
                CodeWhispererCustomization(arn = customization.value.stringValue(), name = customization.variation),
                isOverride = true
            )
        }
        assertThat(sut.activeCustomization(projectRule.project)).isEqualTo(CodeWhispererCustomization("overrideArn", "foo", null))
    }

    @Test
    fun `should update customization when user has never selected one`() {
        val ssoConn = LegacyManagedBearerSsoConnection(region = "us-east-1", startUrl = "url-1", scopes = Q_SCOPES)
        ToolkitConnectionManager.getInstance(projectRule.project).switchConnection(ssoConn)

        // Step 1: Server pushes first customization (arnOverride1)
        abManager.stub {
            on { getCustomizationFeature() }.thenReturn(
                FeatureContext("customizationArnOverride", "foo", FeatureValue.builder().stringValue("arnOverride1").build())
            )
        }
        abManager.getCustomizationFeature()?.let { customization ->
            sut.switchCustomization(
                projectRule.project,
                CodeWhispererCustomization(arn = customization.value.stringValue(), name = customization.variation),
                isOverride = true
            )
        }

        // User should receive arnOverride1 from the server
        assertThat(sut.activeCustomization(projectRule.project))
            .isEqualTo(CodeWhispererCustomization("arnOverride1", "foo", null))

        // Step 2: Server updates customization (arnOverride2)
        abManager.stub {
            on { getCustomizationFeature() }.thenReturn(
                FeatureContext("customizationArnOverride", "foo", FeatureValue.builder().stringValue("arnOverride2").build())
            )
        }

        abManager.getCustomizationFeature()?.let { customization ->
            sut.switchCustomization(
                projectRule.project,
                CodeWhispererCustomization(arn = customization.value.stringValue(), name = customization.variation),
                isOverride = true
            )
        }
        // User should receive arnOverride2 from the server
        assertThat(sut.activeCustomization(projectRule.project))
            .isEqualTo(CodeWhispererCustomization("arnOverride2", "foo", null))
    }

    @Test
    fun `should not override user selection when server updates customization`() {
        val ssoConn = LegacyManagedBearerSsoConnection(region = "us-east-1", startUrl = "url-1", scopes = Q_SCOPES)
        ToolkitConnectionManager.getInstance(projectRule.project).switchConnection(ssoConn)

        // Step 1: Server pushes first customization (arnOverride1)
        abManager.stub {
            on { getCustomizationFeature() }.thenReturn(
                FeatureContext("customizationArnOverride", "foo", FeatureValue.builder().stringValue("arnOverride1").build())
            )
        }

        abManager.getCustomizationFeature()?.let { customization ->
            sut.switchCustomization(
                projectRule.project,
                CodeWhispererCustomization(arn = customization.value.stringValue(), name = customization.variation),
                isOverride = true
            )
        }
        // User should receive arnOverride1 from the server
        assertThat(sut.activeCustomization(projectRule.project))
            .isEqualTo(CodeWhispererCustomization("arnOverride1", "foo", null))

        // Step 2: Server updates customization again (arnOverride2)
        abManager.stub {
            on { getCustomizationFeature() }.thenReturn(
                FeatureContext("customizationArnOverride", "foo", FeatureValue.builder().stringValue("arnOverride2").build())
            )
        }

        abManager.getCustomizationFeature()?.let { customization ->
            sut.switchCustomization(
                projectRule.project,
                CodeWhispererCustomization(arn = customization.value.stringValue(), name = customization.variation),
                isOverride = true
            )
        }
        // Ensure server’s change is applied
        assertThat(sut.activeCustomization(projectRule.project))
            .isEqualTo(CodeWhispererCustomization("arnOverride2", "foo", null))

        // Step 3: User manually selects a different customization (userSelectedArn)
        val userCustomization = CodeWhispererCustomization("userSelectedArn", "userChoice", null)
        sut.switchCustomization(projectRule.project, userCustomization)
        abManager.getCustomizationFeature()?.let { customization ->
            sut.switchCustomization(
                projectRule.project,
                CodeWhispererCustomization(arn = customization.value.stringValue(), name = customization.variation),
                isOverride = true
            )
        }
        // Ensure user selection is still respected (should not change to arnOverride2)
        assertThat(sut.activeCustomization(projectRule.project))
            .isEqualTo(userCustomization)
    }

    @Test
    fun `loadState should load the correct values into memory`() {
        credManager.clear()

        val conn1 = spy(LegacyManagedBearerSsoConnection(region = "us-east-1", startUrl = "url 1", scopes = Q_SCOPES))
        val conn2 = spy(LegacyManagedBearerSsoConnection(region = "us-east-2", startUrl = "url 2", scopes = Q_SCOPES))

        val custom1 = CodeWhispererCustomization("arn-1", "name-1", "description-1")
        val custom2 = CodeWhispererCustomization("arn-2", "name-2", "description-2")

        val state = CodeWhispererCustomizationState().apply {
            this.connectionIdToActiveCustomizationArn.putAll(
                mapOf(
                    conn1.id to custom1,
                    conn2.id to custom2
                )
            )

            this.previousAvailableCustomizations.putAll(
                mapOf(
                    conn1.id to mutableListOf("arn-9", "arn-8", "arn-7"),
                    conn2.id to mutableListOf("arn-6", "arn-5"),
                )
            )
        }

        sut.loadState(state)

        val customizationField = sut::class.memberProperties.find { it.name == "connectionIdToActiveCustomizationArn" }
        assertThat(customizationField).isNotNull

        customizationField?.let {
            it.isAccessible = true
            val connectionToCustomization = it.getter.call(sut) as Map<*, *>
            assertThat(connectionToCustomization).hasSize(2)
            assertThat(connectionToCustomization[conn1.id]).isEqualTo(custom1)
            assertThat(connectionToCustomization[conn2.id]).isEqualTo(custom2)
        }

        val connectionToCustomizationsShownLastTimeFiled = sut::class.memberProperties.find { it.name == "connectionToCustomizationsShownLastTime" }
        assertThat(connectionToCustomizationsShownLastTimeFiled).isNotNull

        connectionToCustomizationsShownLastTimeFiled?.let {
            it.isAccessible = true
            val connectionToCustomizationShownLastTime = it.getter.call(sut) as Map<*, *>
            assertThat(connectionToCustomizationShownLastTime).hasSize(2)
            assertThat(connectionToCustomizationShownLastTime[conn1.id]).isEqualTo(mutableListOf("arn-9", "arn-8", "arn-7"))
            assertThat(connectionToCustomizationShownLastTime[conn2.id]).isEqualTo(mutableListOf("arn-6", "arn-5"))
        }
    }

    @Test
    fun `switchCustomization takes no effect if user is using builder id`() {
        credManager.clear()
        val builderIdConn = spy(LegacyManagedBearerSsoConnection(region = SONO_REGION, startUrl = SONO_URL, scopes = Q_SCOPES))

        ToolkitConnectionManager.getInstance(projectRule.project).switchConnection(builderIdConn)

        assertThat(sut.activeCustomization(projectRule.project)).isNull()
        sut.switchCustomization(projectRule.project, CodeWhispererCustomization("new customization arn"))
        assertThat(sut.activeCustomization(projectRule.project)).isNull()
    }

    @Test
    fun `switchCustomization will update customization for identityCenter users`() {
        credManager.clear()

        val ssoConn = spy(LegacyManagedBearerSsoConnection(region = "us-east-1", startUrl = "url 1", scopes = Q_SCOPES))

        ToolkitConnectionManager.getInstance(projectRule.project).switchConnection(ssoConn)
        assertThat(ssoConn.isSono()).isFalse

        assertThat(sut.activeCustomization(projectRule.project)).isNull()
        sut.switchCustomization(projectRule.project, CodeWhispererCustomization("new customization arn"))
        assertThat(sut.activeCustomization(projectRule.project)).isEqualTo(CodeWhispererCustomization("new customization arn"))
    }

    @Test
    fun `activeCustomization should return customization used by active connection`() {
        credManager.clear()
        val conn1 = spy(LegacyManagedBearerSsoConnection(region = "us-east-1", startUrl = "url 1", scopes = Q_SCOPES))
        val conn2 = spy(LegacyManagedBearerSsoConnection(region = "us-east-2", startUrl = "url 2", scopes = Q_SCOPES))

        ToolkitConnectionManager.getInstance(projectRule.project).switchConnection(conn2)

        val custom1 = CodeWhispererCustomization("arn-1", "name-1", "description-1")
        val custom2 = CodeWhispererCustomization("arn-2", "name-2", "description-2")

        sut::class.memberProperties.find { it.name == "connectionIdToActiveCustomizationArn" }?.let {
            it.isAccessible = true

            @Suppress("UNCHECKED_CAST")
            val connectionToCustomization = it.getter.call(sut) as MutableMap<String, CodeWhispererCustomization>
            connectionToCustomization.putAll(
                listOf(
                    conn1.id to custom1,
                    conn2.id to custom2
                )
            )
        }

        assertThat(sut.activeCustomization(projectRule.project)).isEqualTo(custom2)
    }

    @Test
    fun `invalidateCustomization should remove all`() {
        val conn1 = spy(LegacyManagedBearerSsoConnection(region = "us-east-1", startUrl = "url 1", scopes = Q_SCOPES))
        val conn2 = spy(LegacyManagedBearerSsoConnection(region = "us-east-1", startUrl = "url 1", scopes = Q_SCOPES))
        val conn3 = spy(LegacyManagedBearerSsoConnection(region = "us-east-2", startUrl = "url 2", scopes = Q_SCOPES))

        sut.loadState(
            CodeWhispererCustomizationState().apply {
                this.connectionIdToActiveCustomizationArn.putAll(
                    mapOf(
                        conn1.id to CodeWhispererCustomization("arn_1", "name_1", "description_1"),
                        conn2.id to CodeWhispererCustomization("arn_1", "name_1", "description_1"),
                        conn3.id to CodeWhispererCustomization("arn_2", "name_2", "description_2")
                    )
                )
            }
        )

        sut.invalidateCustomization("arn_1")

        val customizationsField = sut::class.memberProperties.find { it.name == "connectionIdToActiveCustomizationArn" }
        assertThat(customizationsField).isNotNull

        customizationsField?.let {
            it.isAccessible = true
            val actual = it.getter.call(sut) as Map<*, *>
            assertThat(actual[conn1.id]).isNull()
            assertThat(actual[conn2.id]).isNull()
            assertThat(actual[conn3.id]).isEqualTo(CodeWhispererCustomization("arn_2", "name_2", "description_2"))
        }
    }

    @Test
    fun `listCustomization return null if buildId connection`() {
        val connectionManager = ToolkitConnectionManager.getInstance(projectRule.project)
        val builderIdConn = LegacyManagedBearerSsoConnection(region = SONO_REGION, startUrl = SONO_URL, scopes = Q_SCOPES)
        connectionManager.switchConnection(builderIdConn)

        assertThat(connectionManager.activeConnectionForFeature(QConnection.getInstance()).isSono()).isTrue

        val actual = sut.listCustomizations(projectRule.project)
        assertThat(actual).isNull()
    }

    @Test
    fun serialize() {
        val element = xmlElement(
            """
            <component name="codewhispererCustomizationStates">
  </component>
            """.trimIndent()
        )

        val state = CodeWhispererCustomizationState().apply {
            this.previousAvailableCustomizations.putAll(
                mapOf(
                    "fake-sso-url" to mutableListOf("arn_1", "arn_2")
                )
            )

            this.connectionIdToActiveCustomizationArn.putAll(
                mapOf(
                    "fake-sso-url" to CodeWhispererCustomization(
                        arn = "arn_2", name = "name_2", description = "description_2",
                        profile = QRegionProfile(profileName = "myActiveProfile", arn = "arn:aws:codewhisperer:us-west-2:123456789012:profile/myActiveProfile")
                    )
                )
            )

            this.customizationArnOverrideV2 = "arn:aws:codewhisperer:default"
        }

        XmlSerializer.serializeInto(state, element)

        val actual = XMLOutputter().outputString(element)
        val expected = "<component name=\"codewhispererCustomizationStates\">\n" +
            "<option name=\"connectionIdToActiveCustomizationArn\">" +
            "<map>" +
            "<entry key=\"fake-sso-url\">" +
            "<value>" +
            "<CodeWhispererCustomization>" +
            "<option name=\"arn\" value=\"arn_2\" />" +
            "<option name=\"name\" value=\"name_2\" />" +
            "<option name=\"description\" value=\"description_2\" />" +
            "<option name=\"profile\">" +
            "<QRegionProfile>" +
            "<option name=\"arn\" value=\"arn:aws:codewhisperer:us-west-2:123456789012:profile/myActiveProfile\" />" +
            "<option name=\"profileName\" value=\"myActiveProfile\" />" +
            "</QRegionProfile>" +
            "</option>" +
            "</CodeWhispererCustomization>" +
            "</value>" +
            "</entry>" +
            "</map>" +
            "</option>" +
            "<option name=\"customizationArnOverrideV2\" value=\"arn:aws:codewhisperer:default\" />" +
            "<option name=\"previousAvailableCustomizations\">" +
            "<map>" +
            "<entry key=\"fake-sso-url\">" +
            "<value>" +
            "<list>" +
            "<option value=\"arn_1\" />" +
            "<option value=\"arn_2\" />" +
            "</list>" +
            "</value>" +
            "</entry>" +
            "</map>" +
            "</option>" +
            "</component>"

        assertThat(actual).isEqualToIgnoringWhitespace(expected)
    }

    @Test
    fun `deserialize empty data`() {
        val element = xmlElement(
            """
                <component name="codewhispererCustomizationStates">
                </component>
                """
        )
        val actual = XmlSerializer.deserialize(element, CodeWhispererCustomizationState::class.java)
        assertThat(actual.connectionIdToActiveCustomizationArn).hasSize(0)
        assertThat(actual.previousAvailableCustomizations).hasSize(0)
        assertThat(actual.customizationArnOverrideV2).isNull()
    }

    @Test
    fun `deserialize users choosing a customization`() {
        val element = xmlElement(
            """
                <component name="codewhispererCustomizationStates">
                    <option name="connectionIdToActiveCustomizationArn">
                      <map>
                        <entry key="fake-sso-url">
                          <value>
                            <CodeWhispererCustomization>
                              <option name="arn" value="arn_2" />
                              <option name="name" value="name_2" />
                              <option name="description" value="description_2" />
                              <option name="profile"><QRegionProfile>
                              <option name="profileName" value="myActiveProfile"/>
                              <option name="arn" value="arn:aws:codewhisperer:us-west-2:123456789012:profile/myActiveProfile"/>
                            </QRegionProfile></option>
                            </CodeWhispererCustomization>
                          </value>
                        </entry>
                      </map>
                    </option>
                    <option name="previousAvailableCustomizations">
                        <map>
                            <entry key="fake-sso-url">
                                <value>
                                    <list>
                                      <option value="arn_1" />
                                      <option value="arn_2" />
                                      <option value="arn_3" />
                                    </list>
                                </value>
                            </entry>
                        </map>
                    </option>
                    <option name="customizationArnOverrideV2" value="arn:aws:codewhisperer:default"/>
                </component>
            """
        )
        val actual = XmlSerializer.deserialize(element, CodeWhispererCustomizationState::class.java)
        assertThat(actual.customizationArnOverrideV2).isEqualTo("arn:aws:codewhisperer:default")
        assertThat(actual.connectionIdToActiveCustomizationArn).hasSize(1)
        assertThat(actual.connectionIdToActiveCustomizationArn["fake-sso-url"]).isEqualTo(
            CodeWhispererCustomization(
                arn = "arn_2",
                name = "name_2",
                description = "description_2",
                profile = QRegionProfile("myActiveProfile", "arn:aws:codewhisperer:us-west-2:123456789012:profile/myActiveProfile")
            )
        )

        assertThat(actual.previousAvailableCustomizations).hasSize(1)
        assertThat(actual.previousAvailableCustomizations["fake-sso-url"]).isEqualTo(listOf("arn_1", "arn_2", "arn_3"))
    }

    @Test
    fun `backward compatibility - should still be deseriealizable where profile field is not present`() {
        val xml = xmlElement(
            """
                <component name="codewhispererCustomizationStates">
                        <option name="connectionIdToActiveCustomizationArn">
                            <map>
                                <entry key="sso-session:foo">
                                    <value>
                                        <CodeWhispererCustomization>
                                            <option name="arn" value="arn:foo" />
                                            <option name="name" value="Customization-foo" />
                                            <option name="description" value="Foo foo foo foo" />
                                        </CodeWhispererCustomization>
                                    </value>
                                </entry>
                            </map>
                        </option>
                    </component>
            """.trimIndent()
        )

        val actual = XmlSerializer.deserialize(xml, CodeWhispererCustomizationState::class.java)
        val cnt = actual.connectionIdToActiveCustomizationArn.size
        assertThat(cnt).isEqualTo(1)
    }

    @Test
    fun `deserialize users choosing default customization`() {
        val element = xmlElement(
            """
                <component name="codewhispererCustomizationStates">
                    <option name="previousAvailableCustomizations">
                        <map>
                            <entry key="fake-sso-url">
                                <value>
                                    <list>
                                      <option value="arn_1" />
                                      <option value="arn_2" />
                                      <option value="arn_3" />
                                    </list>
                                </value>
                            </entry>
                        </map>
                    </option>
                </component>
            """
        )
        val actual = XmlSerializer.deserialize(element, CodeWhispererCustomizationState::class.java)
        assertThat(actual.connectionIdToActiveCustomizationArn).hasSize(0)
        assertThat(actual.customizationArnOverrideV2).isNull()
        assertThat(actual.previousAvailableCustomizations).hasSize(1)
        assertThat(actual.previousAvailableCustomizations["fake-sso-url"]).isEqualTo(listOf("arn_1", "arn_2", "arn_3"))
    }

    @Ignore
    @Test
    fun `profile switch should keep using existing customization if new list still contains that arn`() {
        val ssoConn = spy(LegacyManagedBearerSsoConnection(region = "us-east-1", startUrl = "url 1", scopes = Q_SCOPES))
        ToolkitConnectionManager.getInstance(projectRule.project).switchConnection(ssoConn)
        val oldCustomization = CodeWhispererCustomization("oldArn", "oldName", "oldDescription")
        sut.switchCustomization(projectRule.project, oldCustomization)

        assertThat(sut.activeCustomization(projectRule.project)).isEqualTo(oldCustomization)
        // TODO: mock sdk client to fix the test
//        val fakeCustomizations = listOf(
//            CodeWhispererCustomization("oldArn", "oldName", "oldDescription")
//        )
//        mockClintAdaptor.stub { on { listAvailableCustomizations(QRegionProfile("fake_name", "fake_arn")) } doReturn fakeCustomizations }

        ApplicationManager.getApplication().messageBus
            .syncPublisher(QRegionProfileSelectedListener.TOPIC)
            .onProfileSelected(projectRule.project, null)

        assertThat(sut.activeCustomization(projectRule.project)).isEqualTo(oldCustomization)
    }

    @Ignore
    @Test
    fun `profile switch should invalidate obsolete customization if it's not in the new list`() {
        val ssoConn = spy(LegacyManagedBearerSsoConnection(region = "us-east-1", startUrl = "url 1", scopes = Q_SCOPES))
        ToolkitConnectionManager.getInstance(projectRule.project).switchConnection(ssoConn)
        val oldCustomization = CodeWhispererCustomization("oldArn", "oldName", "oldDescription")
        sut.switchCustomization(projectRule.project, oldCustomization)
        assertThat(sut.activeCustomization(projectRule.project)).isEqualTo(oldCustomization)

        // TODO: mock sdk client to fix the test
//        val fakeCustomizations = listOf(
//            CodeWhispererCustomization("newArn", "newName", "newDescription")
//        )
//        mockClintAdaptor.stub { on { listAvailableCustomizations(QRegionProfile("fake_name", "fake_arn")) } doReturn fakeCustomizations }

        val latch = CountDownLatch(1)

        ApplicationManager.getApplication().messageBus
            .syncPublisher(QRegionProfileSelectedListener.TOPIC)
            .onProfileSelected(projectRule.project, null)

        latch.await(2, TimeUnit.SECONDS)

        assertThat(sut.activeCustomization(projectRule.project)).isNull()
    }
}
