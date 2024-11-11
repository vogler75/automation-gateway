import React, { useEffect, useState } from "react";
import { usePageTitle } from "../utils/PageTitleContext";
import { Button, Card, Row, Col, message, Input, Tooltip } from "antd";
import {
  PlusCircleOutlined,
  MinusCircleOutlined,
  CopyOutlined,
} from "@ant-design/icons";
import { useAuth } from "../utils/auth";

export function Settings() {
  const endpoint = localStorage.getItem("serverEndpoint") || "localhost";
  const serverURL = `http://${endpoint}:9999`;
  const { setTitle } = usePageTitle();
  const auth = useAuth();
  const [tokens, setTokens] = useState([]);
  const [customQuery, setCustomQuery] = useState("");
  const [apiResponse, setApiResponse] = useState("");

  const handleMutation = async (mutation) => {
    try {
      const response = await fetch(`${serverURL}/admin/api`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: auth.token.replace(/["']/g, ""),
        },
        body: JSON.stringify({ query: mutation }),
      });

      const data = await response.json();
      console.log("Mutation query:", mutation);
      console.log("Mutation response:", data);

      if (data.errors) {
        message.error(data.errors[0].message);
      } else {
        return data.data;
      }
    } catch (error) {
      console.error("Error sending mutation to API:", error);
      message.error("Operation failed!");
    }
  };

  const handleCreateAccessToken = async () => {
    const mutation = `
      mutation {
        createAccessToken(username: "admin", password: "admin") {
          ok
          code
          message
        }
      }
    `;
    const result = await handleMutation(mutation);

    if (result && result.createAccessToken.ok) {
      const newToken = result.createAccessToken.message;
      setTokens((prevTokens) => [...prevTokens, newToken]);
      message.success("Token created successfully.");
    }
  };

  const handleDeleteAccessToken = async (tokenToDelete) => {
    const mutation = `
      mutation {
        deleteAccessToken(token: "${tokenToDelete}") {
          ok
          code
          message
        }
      }
    `;
    const result = await handleMutation(mutation);

    if (result && result.deleteAccessToken.ok) {
      setTokens((prevTokens) =>
        prevTokens.filter((token) => token !== tokenToDelete)
      );
      message.success("Token deleted successfully.");
    }
  };

  const handleCopyToClipboard = async (token) => {
    try {
      await navigator.clipboard.writeText(token);
      message.success("Token copied to clipboard!");
    } catch (err) {
      message.error("Failed to copy token!");
    }
  };

  const handleWriteConfigurationToFile = async () => {
    const mutation = `
      mutation {
        writeConfigurationToFile {
          ok
          code
          message
        }
      }
    `;
    const result = await handleMutation(mutation);

    if (result && result.writeConfigurationToFile.ok) {
      message.success("Configuration saved successfully.");
    }
  };

  const handleCustomQuery = async () => {
    if (!customQuery.trim()) {
      message.warning("Please enter a query or mutation.");
      return;
    }

    try {
      const response = await fetch(`${serverURL}/admin/api`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: auth.token.replace(/["']/g, ""),
        },
        body: JSON.stringify({ query: customQuery }),
      });

      const data = await response.json();
      console.log("Custom query:", customQuery);
      console.log("API response:", data);

      if (data.errors) {
        setApiResponse(JSON.stringify(data.errors, null, 2));
        message.error("Error in query/mutation.");
      } else {
        setApiResponse(JSON.stringify(data.data, null, 2));
        message.success("Query executed successfully.");
      }
    } catch (error) {
      console.error("Error executing custom query:", error);
      message.error("Failed to execute query.");
    }
  };

  const handleEraseQuery = () => {
    setCustomQuery("");
    setApiResponse("");
    document.getElementById("custom-query-area").rows = 8;
    document.getElementById("response-area").rows = 8;
  };

  useEffect(() => {
    setTitle("Gateway Settings");
  }, []);

  return (
    <>
      <Row gutter={16} style={{ marginBottom: 12, marginTop: 12 }}>
        <Col span={18}>
          <Card title="Configuration persistency">
            <Button
              onClick={handleWriteConfigurationToFile}
              type="submit"
              className="btn btn-success"
              htmlType="submit"
              style={{
                padding: "10px 20px",
                lineHeight: "normal",
                display: "flex",
                alignItems: "center",
                justifyContent: "center",
              }}
            >
              Save to gateway config file
            </Button>
          </Card>
        </Col>
        <Col span={6}>
          <div
            style={{
              padding: "8px 16px",
              backgroundColor: "#f0f0f0",
              borderRadius: 4,
              height: "100%",
            }}
          >
            <p>Click to save the current configuration to the gateway.</p>
            <p>This will persist settings changes.</p>
          </div>
        </Col>
      </Row>

      <Row gutter={16} style={{ marginTop: 24 }}>
        <Col span={18}>
          <Card title="API Tokens">
            <Button
              type="submit"
              className="btn btn-success"
              htmlType="submit"
              style={{
                padding: "10px 20px",
                lineHeight: "normal",
                display: "flex",
                alignItems: "center",
                justifyContent: "center",
              }}
              onClick={handleCreateAccessToken}
              icon={<PlusCircleOutlined />}
            >
              Create Token
            </Button>
            <div style={{ marginTop: 12 }}>
              {tokens.map((token, index) => (
                <Row gutter={16} key={index} style={{ marginBottom: 6 }}>
                  <Col span={16}>
                    <Input value={token} readOnly />
                  </Col>
                  <Col span={4}>
                    <Tooltip title="Copy to clipboard">
                      <Button
                        icon={<CopyOutlined />}
                        onClick={() => handleCopyToClipboard(token)}
                      />
                    </Tooltip>
                  </Col>
                  <Col span={4}>
                    <Button
                      type="primary"
                      danger
                      onClick={() => handleDeleteAccessToken(token)}
                    >
                      <MinusCircleOutlined /> Delete
                    </Button>
                  </Col>
                </Row>
              ))}
            </div>
          </Card>
        </Col>
        <Col span={6}>
          <div
            style={{
              padding: "8px 16px",
              backgroundColor: "#f0f0f0",
              borderRadius: 4,
              height: "100%",
            }}
          >
            <p>Click to create a temporary access token for 30 minutes.</p>
            <p>
              Use this token for quick access and delete it when no longer
              needed.
            </p>
          </div>
        </Col>
      </Row>

      <Row gutter={16} style={{ marginTop: 24 }}>
        <Col span={18}>
          <Card title="On-Demand GraphQL Query/Mutation">
            <Row gutter={16}>
              <Col span={12}>
                <Input.TextArea
                  id="custom-query-area"
                  value={customQuery}
                  onChange={(e) => setCustomQuery(e.target.value)}
                  placeholder="Enter your custom GraphQL query or mutation here"
                  rows={8}
                  className="green-border-on-hover-focus"
                />
                <div
                  style={{
                    display: "flex",
                    alignItems: "center",
                    marginTop: "6px",
                  }}
                >
                  <Button
                    type="submit"
                    className="btn btn-success"
                    onClick={handleCustomQuery}
                    style={{
                      marginRight: 8,
                      padding: "10px 20px",
                      lineHeight: "normal",
                      display: "inline-flex",
                      alignItems: "center",
                      justifyContent: "center",
                    }}
                  >
                    Send Query
                  </Button>
                  <Button
                    className="green-border-text-hover-focus"
                    onClick={handleEraseQuery}
                    style={{
                      display: "inline-flex",
                      alignItems: "center",
                      justifyContent: "center",
                      padding: "10px 20px",
                    }}
                  >
                    Erase
                  </Button>
                </div>
              </Col>
              <Col span={12}>
                <Input.TextArea
                  id="response-area"
                  value={apiResponse}
                  readOnly
                  placeholder="Response will appear here"
                  rows={8}
                  className="green-border-on-hover-focus"
                />
              </Col>
            </Row>
          </Card>
        </Col>
        <Col span={6}>
          <div
            style={{
              padding: "8px 16px",
              backgroundColor: "#f0f0f0",
              borderRadius: 4,
              height: "100%",
            }}
          >
            <p>Use this space to test custom GraphQL queries or mutations.</p>
            <p>Send a query to view its response on the right.</p>
          </div>
        </Col>
      </Row>
    </>
  );
}
