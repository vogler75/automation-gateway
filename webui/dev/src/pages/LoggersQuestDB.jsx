import React, { useEffect, useState } from "react";
import { usePageTitle } from "../utils/PageTitleContext";
import {
  CloseOutlined,
  CheckOutlined,
  PlusOutlined,
  PlusCircleOutlined,
  MinusCircleOutlined,
  MenuOutlined,
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
  Tooltip,
} from "antd";
import { useAuth } from "../utils/auth";

const { Option } = Select;

const handleMenuClick = async (key, id, auth, fetchData) => {
  const endpoint = localStorage.getItem("serverEndpoint") || "localhost";
  const serverURL = `http://${endpoint}:9999`;
  let mutation = "";

  if (key === "enable") {
    mutation = `
      mutation {
        enableComponent(type: QuestDBLogger, id: "${id}") {
          ok
          code
          message
        }
      }
    `;
  } else if (key === "disable") {
    mutation = `
      mutation {
        disableComponent(type: QuestDBLogger, id: "${id}") {
          ok
          code
          message
        }
      }
    `;
  } else if (key === "delete") {
    mutation = `
      mutation {
        deleteComponent(type: QuestDBLogger, id: "${id}") {
          ok
          code
          message
        }
      }
    `;
  }

  if (mutation) {
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
        message.success(
          `${key.charAt(0).toUpperCase() + key.slice(1)} operation successful!`
        );

        await fetchData();
      }
    } catch (error) {
      console.error("Error sending mutation to API:", error);
      message.error("Operation failed!");
    }
  }
};

export function LoggersQuestDB() {
  const endpoint = localStorage.getItem("serverEndpoint") || "localhost";
  const serverURL = `http://${endpoint}:9999`;
  const [form] = Form.useForm();
  const { setTitle } = usePageTitle();
  const auth = useAuth();
  const [apiData, setApiData] = useState([]);
  const [loading, setLoading] = useState(true);
  const [isFormVisible, setIsFormVisible] = useState(false);
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [topicFields, setTopicFields] = useState([{ key: Math.random() }]);

  const fetchData = async () => {
    const query = `{
      getComponents(group: Logger, type: QuestDBLogger) {
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
      const response = await fetch(`${serverURL}/admin/api`, {
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
    setTitle("QuestDB Loggers Configuration");
    fetchData();
  }, []);

  const handleToggleForm = () => {
    if (isFormVisible) {
      form.resetFields();
    }
    setIsFormVisible(!isFormVisible);
  };

  const handleSubmit = async (values) => {
    const endpoint = localStorage.getItem("serverEndpoint") || "localhost";
    const serverURL = `http://${endpoint}:9999`;
    console.log("Form values:", values);

    const loggingTopics = (values.loggingTopics || []).map((t) => ({
      Topic: t.Topic,
    }));

    const dataObject = {
      Id: values.Id,
      Enabled: values.Enabled || false,
      LogLevel: values.LogLevel || "",
      Url: values.Url || "",
      Version: values.Version || 1,
      Database: values.Database || "",
      WriteParameters: {
        QueueType: values.WriteParameters?.QueueType || "MEMORY",
      },
      Logging: loggingTopics,
    };

    const configString = JSON.stringify(dataObject);

    const query = `
    mutation {
      createComponent(type: QuestDBLogger, config: "${configString.replace(
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
      const response = await fetch(`${serverURL}/admin/api`, {
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
      <div>
        <Button
          onClick={handleToggleForm}
          className="custom-button"
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
          form={form}
          name="questdb_logger_form"
          style={{
            maxWidth: "100%",
            width: "100%",
            backgroundColor: "#f1f1f1",
            border: "1px solid #d9d9d9",
            borderRadius: "4px",
            paddingLeft: "16px",
            paddingRight: "16px",
          }}
          autoComplete="off"
          onFinish={(values) => handleSubmit(values)}
          labelAlign="left"
        >
          <Row gutter={16}>
            <Col span={12} style={{ marginTop: 10 }}>
              <Form.Item
                label="Id:"
                name="Id"
                rules={[{ required: true, message: "Id is required" }]}
                labelCol={{ span: 6 }}
                wrapperCol={{ span: 16 }}
              >
                <Input placeholder="e.g., influx" />
              </Form.Item>
              <Form.Item
                label="Log Level:"
                name="LogLevel"
                initialValue="INFO"
                labelCol={{ span: 6 }}
                wrapperCol={{ span: 16 }}
              >
                <Select placeholder="Select Log Level">
                  <Option value="INFO">INFO</Option>
                  <Option value="ALL">ALL</Option>
                  <Option value="SEVERE">SEVERE</Option>
                  <Option value="WARNING">WARNING</Option>
                  <Option value="CONFIG">CONFIG</Option>
                  <Option value="FINE">FINE</Option>
                  <Option value="FINER">FINER</Option>
                  <Option value="FINEST">FINEST</Option>
                  <Option value="OFF">OFF</Option>
                </Select>
              </Form.Item>
              <Form.Item
                label="Url:"
                name="Url"
                rules={[
                  { required: true, message: "Please input a valid URL!" },
                ]}
                labelCol={{ span: 6 }}
                wrapperCol={{ span: 16 }}
              >
                <Input placeholder="e.g., http://192.168.0.123:8086" />
              </Form.Item>
              <Form.Item
                label="Database:"
                name="Database"
                labelCol={{ span: 6 }}
                wrapperCol={{ span: 16 }}
              >
                <Input placeholder="e.g., scada1" />
              </Form.Item>
              <Form.Item
                label="Username:"
                name="Username"
                labelCol={{ span: 6 }}
                wrapperCol={{ span: 16 }}
              >
                <Input placeholder="e.g., user" />
              </Form.Item>
              <Form.Item
                label="Password:"
                name="Password"
                labelCol={{ span: 6 }}
                wrapperCol={{ span: 16 }}
              >
                <Input.Password placeholder="e.g., password" />
              </Form.Item>
            </Col>

            <Col span={12} style={{ marginTop: 10 }}>
              <Form.Item
                label="Enabled:"
                name="Enabled"
                valuePropName="checked"
                labelCol={{ span: 6 }}
                wrapperCol={{ span: 16 }}
              >
                <Switch
                  checkedChildren={<CheckOutlined />}
                  unCheckedChildren={<CloseOutlined />}
                />
              </Form.Item>
              <Form.Item
                label="Version:"
                name="Version"
                initialValue={1}
                labelCol={{ span: 6 }}
                wrapperCol={{ span: 18 }}
              >
                <Select>
                  <Option value={1}>1</Option>
                  <Option value={2}>2</Option>
                </Select>
              </Form.Item>
              <Form.Item
                label="Org:"
                name="Org"
                labelCol={{ span: 6 }}
                wrapperCol={{ span: 16 }}
              >
                <Input placeholder="e.g., organization" />
              </Form.Item>
              <Form.Item
                label="Bucket:"
                name="Bucket"
                labelCol={{ span: 6 }}
                wrapperCol={{ span: 16 }}
              >
                <Input placeholder="e.g., bucket" />
              </Form.Item>
              <Form.Item
                label="Token:"
                name="Token"
                labelCol={{ span: 6 }}
                wrapperCol={{ span: 16 }}
              >
                <Input placeholder="e.g., token" />
              </Form.Item>
              <Form.Item
                label="Measurement:"
                name="Measurement"
                labelCol={{ span: 6 }}
                wrapperCol={{ span: 16 }}
              >
                <Input placeholder="e.g., measurement" />
              </Form.Item>
            </Col>
          </Row>

          <Form.Item label="Write Parameters" style={{ marginTop: 20 }}>
            <Row gutter={16}>
              <Col span={12}>
                <Form.Item
                  label="Queue Type:"
                  name={["WriteParameters", "QueueType"]}
                  initialValue="MEMORY"
                  labelCol={{ span: 6 }}
                  wrapperCol={{ span: 16 }}
                >
                  <Select placeholder="Select Queue Type">
                    <Option value="MEMORY">MEMORY</Option>
                    <Option value="DISK">DISK</Option>
                  </Select>
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item
                  label="Queue Size:"
                  name={["WriteParameters", "QueueSize"]}
                  labelCol={{ span: 6 }}
                  wrapperCol={{ span: 16 }}
                >
                  <Input placeholder="e.g., 20000" />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item
                  label="Block Size:"
                  name={["WriteParameters", "BlockSize"]}
                  labelCol={{ span: 6 }}
                  wrapperCol={{ span: 16 }}
                >
                  <Input placeholder="e.g., 10000" />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item
                  label="Disk Path:"
                  name={["WriteParameters", "DiskPath"]}
                  labelCol={{ span: 6 }}
                  wrapperCol={{ span: 16 }}
                >
                  <Input placeholder="e.g., /path/to/disk" />
                </Form.Item>
              </Col>
            </Row>
          </Form.Item>

          <Form.List name="loggingTopics" initialValue={[{ Topic: "" }]}>
            {(fields, { add, remove }) => (
              <>
                <Row gutter={16} style={{ marginBottom: "10px" }}>
                  <Col
                    span={24}
                    style={{ display: "flex", alignItems: "center" }}
                  >
                    <h4 style={{ margin: 0, marginRight: 16 }}>
                      Logging Topics:
                    </h4>
                    <div style={{ display: "flex", gap: "8px" }}>
                      <Button type="dashed" onClick={() => add()}>
                        <PlusCircleOutlined /> Add Topic
                      </Button>
                      <Button
                        type="dashed"
                        onClick={() => remove(fields.length - 1)}
                        disabled={fields.length <= 1}
                      >
                        <MinusCircleOutlined /> Remove Topic
                      </Button>
                    </div>
                  </Col>
                </Row>
                {fields.map((field, index) => (
                  <Row gutter={16} key={field.key} style={{ marginBottom: 0 }}>
                    <Col span={24}>
                      <Form.Item
                        {...field}
                        label={`Topic ${index + 1}:`}
                        name={[field.name, "Topic"]}
                        fieldKey={[field.fieldKey, "Topic"]}
                        rules={[
                          { required: true, message: "Topic is required" },
                        ]}
                        labelCol={{ span: 3 }}
                        wrapperCol={{ span: 18 }}
                      >
                        <Input placeholder="e.g., opc/s71200/path/Objects/ServerInterfaces/SI1/#" />
                      </Form.Item>
                    </Col>
                  </Row>
                ))}
              </>
            )}
          </Form.List>
          <Form.Item
            wrapperCol={{ span: 24 }}
            style={{ display: "flex", justifyContent: "center" }}
          >
            <Button
              type="submit"
              className="btn btn-success"
              htmlType="submit"
              style={{
                margin: "0 auto",
                textAlign: "center",
                padding: "10px 20px",
                lineHeight: "normal",
                display: "flex",
                alignItems: "center",
                justifyContent: "center",
              }}
            >
              Add Configuration
            </Button>
          </Form.Item>
        </Form>
      )}
      <Row gutter={[16, 16]} style={{ marginTop: "16px" }}>
        {loading ? (
          <p></p>
        ) : apiData && Array.isArray(apiData) && apiData.length > 0 ? (
          apiData.map((item) => (
            <Col span={6} key={item.id}>
              <Card
                title={
                  item.id.length > 20 ? (
                    <Tooltip title={item.id}>
                      <div className="card-title">
                        {item.id.substring(0, 20) + "..."}
                      </div>
                    </Tooltip>
                  ) : (
                    <div className="card-title">{item.id}</div>
                  )
                }
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
                    trigger={["click"]}
                  >
                    <Button className="btn-action-custom">
                      <MenuOutlined />
                    </Button>
                  </Dropdown>
                }
                style={{ backgroundColor: "#cceeff" }}
              >
                <p>
                  <strong>Type:</strong> {item.type}
                </p>
                <p>
                  <strong>Status:</strong> {item.status}
                </p>
                <p>
                  <strong>Url:</strong> {JSON.parse(item.config).Url}
                </p>
                <p>
                  <strong>Database:</strong> {JSON.parse(item.config).Database}
                </p>
                <p>
                  <strong>Log Level:</strong> {JSON.parse(item.config).LogLevel}
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
