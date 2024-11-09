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
        enableComponent(type: MqttDriver, id: "${id}") {
          ok
          code
          message
        }
      }
    `;
  } else if (key === "disable") {
    mutation = `
      mutation {
        disableComponent(type: MqttDriver, id: "${id}") {
          ok
          code
          message
        }
      }
    `;
  } else if (key === "delete") {
    mutation = `
      mutation {
        deleteComponent(type: MqttDriver, id: "${id}") {
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

        // Directly fetch data without delay
        await fetchData(); // Fetch data after mutation completes
      }
    } catch (error) {
      console.error("Error sending mutation to API:", error);
      message.error("Operation failed!");
    }
  }
};

export function DriversMqtt() {
  const [form] = Form.useForm();
  const { setTitle } = usePageTitle();
  const auth = useAuth();
  const [apiData, setApiData] = useState([]);
  const [loading, setLoading] = useState(true);
  const [isFormVisible, setIsFormVisible] = useState(false);

  const fetchData = async () => {
    const query = `{
      getComponents(group: Driver, type: MqttDriver) {
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
    setTitle("MQTT Drivers Configuration");
    fetchData();
  }, []);

  const handleToggleForm = () => {
    if (isFormVisible) {
      form.resetFields();
    }
    setIsFormVisible(!isFormVisible);
  };

  const handleSubmit = async (values) => {
    console.log("Form values:", values);

    const dataObject = {
      Id: values.items[0]?.Id,
      Enabled: values.items[0]?.Enabled || false,
      LogLevel: values.items[0]?.LogLevel || "INFO",
      Host: values.items[0]?.Host || "192.168.1.1",
      Port: Number(values.items[0]?.Port) || 1883,
      Format: values.items[0]?.Format || "Json",
    };

    const configString = JSON.stringify(dataObject);

    const query = `
      mutation {
        createComponent(type: MqttDriver, config: "${configString.replace(
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

        form.resetFields();
        fetchData();
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
          name="dynamic_form_complex"
          style={{
            maxWidth: "100%",
            width: "100%",
            backgroundColor: "#f1f1f1",
          }}
          autoComplete="off"
          initialValues={{ items: [{}] }}
          onFinish={handleSubmit}
          labelAlign="left" // Align all labels to the left
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
                          label="Host:"
                          name={[field.name, "Host"]}
                          rules={[
                            {
                              required: true,
                              message: "Please input a valid Host!",
                            },
                          ]}
                          labelCol={{ span: 8 }}
                          wrapperCol={{ span: 16 }}
                          labelAlign="left"
                        >
                          <Input placeholder="192.168.1.3" />
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
                          label="Format:"
                          name={[field.name, "Format"]}
                          labelCol={{ span: 8 }}
                          wrapperCol={{ span: 16 }}
                          labelAlign="left"
                        >
                          <Select
                            placeholder="Select Format"
                            defaultValue="Json"
                          >
                            <Option value="Json">Json</Option>
                            <Option value="Xml" disabled>
                              Xml
                            </Option>
                          </Select>
                        </Form.Item>
                        <Form.Item
                          label="Port:"
                          name={[field.name, "Port"]}
                          rules={[
                            {
                              required: true,
                              message: "Please input a valid port number!",
                            },
                          ]}
                          labelCol={{ span: 8 }}
                          wrapperCol={{ span: 16 }}
                          labelAlign="left"
                        >
                          <Input placeholder="1883" />
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
        {loading ? (
          <p></p>
        ) : apiData && Array.isArray(apiData) && apiData.length > 0 ? (
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
                            handleMenuClick("enable", item.id, auth, fetchData)
                          }
                        >
                          Enable
                        </Menu.Item>
                        <Menu.Item
                          key="disable"
                          onClick={() =>
                            handleMenuClick("disable", item.id, auth, fetchData)
                          }
                        >
                          Disable
                        </Menu.Item>
                        <Menu.Item
                          key="delete"
                          onClick={() =>
                            handleMenuClick("delete", item.id, auth, fetchData)
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
                  <strong>Host:</strong> {JSON.parse(item.config).Host}:
                  {JSON.parse(item.config).Port}
                </p>
                <p>
                  <strong>Log Level:</strong> {JSON.parse(item.config).LogLevel}
                </p>
                <p>
                  <strong>Format:</strong> {JSON.parse(item.config).Format}
                </p>
                {Array.isArray(item.messages) && item.messages.length > 0 ? (
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
                ) : (
                  <p>No messages available.</p>
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
