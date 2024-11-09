import React, { useEffect, useState } from "react";
import { usePageTitle } from "../utils/PageTitleContext";
import {
  CloseOutlined,
  CheckOutlined,
  DownOutlined,
  PlusOutlined,
} from "@ant-design/icons";
import {
  Button,
  Card,
  Col,
  Row,
  Form,
  Input,
  Switch,
  Dropdown,
  Menu,
  message,
  Select,
} from "antd";
import { useAuth } from "../utils/auth";

const { Option } = Select;

const handleMenuClick = async (key, id, auth, fetchData) => {
  let mutation = "";

  if (key === "enable") {
    mutation = `
      mutation {
        enableComponent(type: OpcUaDriver, id: "${id}") {
          ok
          code
          message
        }
      }
    `;
  } else if (key === "disable") {
    mutation = `
      mutation {
        disableComponent(type: OpcUaDriver, id: "${id}") {
          ok
          code
          message
        }
      }
    `;
  } else if (key === "delete") {
    mutation = `
      mutation {
        deleteComponent(type: OpcUaDriver, id: "${id}") {
          ok
          code
          message
        }
      }
    `;
  }

  if (mutation) {
    try {
      const response = await fetch("http://localhost:9999/admin/api", {
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
        message.success(
          `${key.charAt(0).toUpperCase() + key.slice(1)} operation successful!`
        );

        // Fetch updated data after deletion
        setTimeout(async function () {
          fetchData();
        }, 250);
        //await fetchData();
      }
    } catch (error) {
      console.error("Error sending mutation to API:", error);
      message.error("Operation failed!");
    }
  }
};

export function DriversOpcua() {
  const [form] = Form.useForm();
  const { setTitle } = usePageTitle();
  const auth = useAuth();
  const [apiData, setApiData] = useState([]);
  const [loading, setLoading] = useState(true);
  const [isFormVisible, setIsFormVisible] = useState(false);

  const fetchData = async () => {
    const query = `{
      getComponents(group: Driver, type: OpcUaDriver) {
        id
        type
        status
        config
        messages(last: 1) {
          time
          level
          name
          message
        }
      }
    }`;

    try {
      const response = await fetch("http://localhost:9999/admin/api", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: auth.token.replace(/["']/g, ""),
        },
        body: JSON.stringify({ query }),
      });

      const data = await response.json();
      setApiData(data?.data?.getComponents || []);
      console.log("API response:", data);
    } catch (error) {
      console.error("Error fetching data from API:", error);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    setTitle("OPC UA Drivers Configuration");
    fetchData();
  }, []);

  const handleToggleForm = () => {
    if (isFormVisible) {
      form.resetFields(); // Reset form fields when hiding the form
    }
    setIsFormVisible(!isFormVisible); // Toggle the form visibility
  };

  const handleSubmit = async (values) => {
    console.log("Form values:", values);

    const dataObject = {
      Id: values.items[0]?.Id,
      Enabled: values.items[0]?.Enabled || false,
      LogLevel: values.items[0]?.LogLevel || "INFO",
      EndpointUrl: values.items[0]?.Endpoint || "opc.tcp://127.0.0.1:4849/",
      UpdateEndpointUrl: values.items[0]?.UpdateEndpointUrl || true,
      SecurityPolicyUri:
        values.items[0]?.SecurityPolicyUri ||
        "http://opcfoundation.org/UA/SecurityPolicy#None",
      // Ensure these values are numbers
      KeepAliveFailuresAllowed:
        Number(values.items[0]?.KeepAliveFailuresAllowed) || 0,
      SubscriptionSamplingInterval:
        Number(values.items[0]?.SubscriptionSamplingInterval) || 0.0,
    };

    // Convert the object to a JSON string
    const configString = JSON.stringify(dataObject);

    const query = `
      mutation {
        createComponent(type: OpcUaDriver, config: "${configString.replace(
          /"/g,
          '\\"'
        )}") {
          ok
          code
          message
        }
      }
    `;

    try {
      const response = await fetch("http://localhost:9999/admin/api", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: auth.token.replace(/["']/g, ""),
        },
        body: JSON.stringify({ query }),
      });

      const data = await response.json();
      if (data.errors) {
        message.error(data.errors[0].message);
      } else {
        message.success("Component created successfully!");

        // Reset the form fields after successful creation
        form.resetFields();

        setTimeout(async function () {
          fetchData();
        }, 250);

        //fetchData(); // Refresh data after creation
      }
    } catch (error) {
      console.error("Error sending data to API:", error);
      message.error("Creation failed!");
    }
  };

  return (
    <>
      <p></p>
      <div style={{}}>
        <Button
          onClick={handleToggleForm}
          style={{
            width: "100%",
            display: "flex",
            justifyContent: "center",
            alignItems: "center",
          }}
        >
          {isFormVisible ? (
            <CloseOutlined style={{ fontSize: "16px" }} />
          ) : (
            <PlusOutlined style={{ fontSize: "16px" }} />
          )}
        </Button>
      </div>
      {isFormVisible && (
        <Form
          labelCol={{ span: 8 }}
          wrapperCol={{ span: 16 }}
          form={form}
          name="opcuadriver_form"
          style={{
            maxWidth: "100%",
            width: "100%",
            backgroundColor: "#f1f1f1",
          }}
          autoComplete="off"
          initialValues={{ items: [{}] }}
          onFinish={handleSubmit}
          labelAlign="left"
        >
          <Form.List name="items">
            {(fields, { add, remove }) => (
              <div
                style={{ display: "flex", flexDirection: "column", rowGap: 16 }}
              >
                {fields.map((field) => (
                  <div
                    key={field.key}
                    style={{
                      border: "1px solid #d9d9d9",
                      padding: "16px",
                      borderRadius: "4px",
                    }}
                  >
                    <Row gutter={16}>
                      <Col span={12}>
                        <Form.Item
                          label="Id:"
                          name={[field.name, "Id"]}
                          rules={[
                            { required: true, message: "Id is required" },
                          ]}
                          labelCol={{ span: 8 }}
                          wrapperCol={{ span: 16 }}
                          labelAlign="left"
                        >
                          <Input placeholder="Component Name" />
                        </Form.Item>
                        <Form.Item
                          label="Log Level:"
                          name={[field.name, "LogLevel"]}
                          labelCol={{ span: 8 }}
                          wrapperCol={{ span: 16 }}
                          labelAlign="left"
                        >
                          <Select
                            placeholder="Select Log Level"
                            defaultValue="INFO"
                          >
                            <Option value="INFO">INFO</Option>
                            <Option value="WARNING">WARNING</Option>
                            <Option value="ERROR">ERROR</Option>
                            <Option value="CRITICAL">CRITICAL</Option>
                          </Select>
                        </Form.Item>
                        <Form.Item
                          label="Endpoint:"
                          name={[field.name, "Endpoint"]}
                          rules={[
                            {
                              required: true,
                              message: "Please input a valid Endpoint URL!",
                            },
                            {
                              pattern:
                                /^opc\.tcp:\/\/([a-zA-Z0-9.-]+):\d{1,5}$/,
                              message: "Invalid Endpoint format.",
                            },
                          ]}
                          labelCol={{ span: 8 }}
                          wrapperCol={{ span: 16 }}
                          labelAlign="left"
                        >
                          <Input placeholder="opc.tcp://192.168.1.3:62540/server" />
                        </Form.Item>
                        <Form.Item
                          label="Subscription Sampling Interval:"
                          name={[field.name, "SubscriptionSamplingInterval"]}
                          labelCol={{ span: 12 }}
                          wrapperCol={{ span: 12 }}
                          labelAlign="left"
                        >
                          <Input placeholder="1.5" />
                        </Form.Item>
                      </Col>
                      <Col span={12}>
                        <Form.Item
                          label="Enabled:"
                          name={[field.name, "Enabled"]}
                          labelCol={{ span: 8 }}
                          wrapperCol={{ span: 16 }}
                          labelAlign="left"
                        >
                          <Switch
                            checkedChildren={<CheckOutlined />}
                            unCheckedChildren={<CloseOutlined />}
                          />
                        </Form.Item>
                        <Form.Item
                          label="Update Endpoint Url:"
                          name={[field.name, "UpdateEndpointUrl"]}
                          labelCol={{ span: 8 }}
                          wrapperCol={{ span: 16 }}
                          labelAlign="left"
                        >
                          <Switch
                            checkedChildren={<CheckOutlined />}
                            unCheckedChildren={<CloseOutlined />}
                          />
                        </Form.Item>
                        <Form.Item
                          label="Security Policy Uri:"
                          name={[field.name, "SecurityPolicyUri"]}
                          labelCol={{ span: 8 }}
                          wrapperCol={{ span: 16 }}
                          labelAlign="left"
                        >
                          <Select
                            placeholder="Select Security Policy"
                            defaultValue="http://opcfoundation.org/UA/SecurityPolicy#None"
                          >
                            <Option value="http://opcfoundation.org/UA/SecurityPolicy#None">
                              None
                            </Option>
                            <Option
                              value="http://opcfoundation.org/UA/SecurityPolicy#Basic256Sha256"
                              disabled
                            >
                              Basic256Sha256
                            </Option>
                            <Option
                              value="http://opcfoundation.org/UA/SecurityPolicy#Aes256_Sha256_RsaPss"
                              disabled
                            >
                              Aes256_Sha256_RsaPss
                            </Option>
                            <Option
                              value="http://opcfoundation.org/UA/SecurityPolicy#Aes128_Sha256_RsaOaep"
                              disabled
                            >
                              Aes128_Sha256_RsaOaep
                            </Option>
                          </Select>
                        </Form.Item>
                        <Form.Item
                          label="Keep Alive Failures Allowed:"
                          name={[field.name, "KeepAliveFailuresAllowed"]}
                          labelCol={{ span: 12 }}
                          wrapperCol={{ span: 12 }}
                          labelAlign="left"
                        >
                          <Input placeholder="2" />
                        </Form.Item>
                      </Col>
                    </Row>
                    <div style={{ textAlign: "center" }}>
                      <Button
                        type="primary"
                        htmlType="submit"
                        style={{ display: "block", margin: "0 auto" }}
                      >
                        Add Configuration
                      </Button>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </Form.List>
        </Form>
      )}
      <Row gutter={[16, 16]} style={{ marginTop: "16px" }}>
        {apiData && Array.isArray(apiData) && apiData.length > 0 ? (
          apiData.map((item) => (
            <Col span={6} key={item.id}>
              <Card
                title={<div className="card-title">{item.id}</div>}
                bordered={false}
                extra={
                  <Dropdown
                    overlay={
                      <Menu>
                        <Menu.Item
                          key="enable"
                          onClick={() =>
                            handleMenuClick(
                              "enable",
                              item.id,
                              auth,
                              setApiData,
                              fetchData
                            )
                          }
                        >
                          Enable
                        </Menu.Item>
                        <Menu.Item
                          key="disable"
                          onClick={() =>
                            handleMenuClick(
                              "disable",
                              item.id,
                              auth,
                              setApiData,
                              fetchData
                            )
                          }
                        >
                          Disable
                        </Menu.Item>
                        <Menu.Item
                          key="delete"
                          onClick={() =>
                            handleMenuClick(
                              "delete",
                              item.id,
                              auth,
                              setApiData,
                              fetchData
                            )
                          }
                          danger
                        >
                          Delete
                        </Menu.Item>
                      </Menu>
                    }
                  >
                    <Button>
                      Actions <DownOutlined />
                    </Button>
                  </Dropdown>
                }
                style={{ backgroundColor: "#d5f5d5" }}
              >
                <p>
                  <strong>Type:</strong> {item.type}
                </p>
                <p>
                  <strong>Status:</strong> {item.status}
                </p>
                <p>
                  <strong>Endpoint:</strong>{" "}
                  {JSON.parse(item.config).EndpointUrl}
                </p>
                <p>
                  <strong>Log Level:</strong> {JSON.parse(item.config).LogLevel}
                </p>
                {item.messages && item.messages.length > 0 && (
                  <div className="last-message-container">
                    <p>
                      <strong>Last Message:</strong>
                    </p>
                    <p>
                      <strong>Time:</strong> {item.messages[0].time}
                    </p>
                    <p>
                      <strong>Level:</strong> {item.messages[0].level}
                    </p>
                    <p>
                      <strong>Message:</strong> {item.messages[0].message}
                    </p>
                  </div>
                )}
              </Card>
            </Col>
          ))
        ) : (
          <></>
        )}
      </Row>
    </>
  );
}
