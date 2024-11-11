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
import ShowMoreLogsPopup from "../components/ShowMoreLogsPopup";

const { Option } = Select;

const handleMenuClick = async (key, id, auth, fetchData) => {
  const endpoint = localStorage.getItem("serverEndpoint") || "localhost";
  const serverURL = `http://${endpoint}:9999`;
  let mutation = "";

  if (key === "enable") {
    mutation = `
      mutation {
        enableComponent(type: OpcUaServer, id: "${id}") {
          ok
          code
          message
        }
      }
    `;
  } else if (key === "disable") {
    mutation = `
      mutation {
        disableComponent(type: OpcUaServer, id: "${id}") {
          ok
          code
          message
        }
      }
    `;
  } else if (key === "delete") {
    mutation = `
      mutation {
        deleteComponent(type: OpcUaServer, id: "${id}") {
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

export function ServersOpcua() {
  const [isPopupVisible, setIsPopupVisible] = useState(false);
  const [selectedItem, setSelectedItem] = useState(null);
  const endpoint = localStorage.getItem("serverEndpoint") || "localhost";
  const serverURL = `http://${endpoint}:9999`;
  const [form] = Form.useForm();
  const { setTitle } = usePageTitle();
  const auth = useAuth();
  const [apiData, setApiData] = useState([]);
  const [loading, setLoading] = useState(true);
  const [isFormVisible, setIsFormVisible] = useState(false);
  const [topicFields, setTopicFields] = useState([{ key: Math.random() }]);

  const fetchData = async () => {
    const query = `{
      getComponents(group: Server, type: OpcUaServer) {
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
    setTitle("OPC UA Servers Configuration");
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

    const topics = values.topics.map((t) => ({ Topic: t.topic }));

    const dataObject = {
      Id: values.Id,
      Enabled: values.Enabled || false,
      LogLevel: values.LogLevel || "INFO",
      Port: Number(values.Port) || 4841,
      Topics: topics,
    };

    const configString = JSON.stringify(dataObject);

    const query = `
      mutation {
        createComponent(type: OpcUaServer, config: "${configString.replace(
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

  const handleShowPopup = (item) => {
    setSelectedItem(item);
    setIsPopupVisible(true);
  };

  const handleClosePopup = () => {
    setIsPopupVisible(false);
    setSelectedItem(null);
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
          name="mqtt_form"
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
                <Input placeholder="Component Name" />
              </Form.Item>

              <Form.Item
                label="Port:"
                name="Port"
                rules={[
                  {
                    message: "Please input a valid port number!",
                  },
                ]}
                labelCol={{ span: 6 }}
                wrapperCol={{ span: 16 }}
              >
                <Input placeholder="4841" />
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
            </Col>
          </Row>
          <Form.List name="topics" initialValue={[{ topic: "" }]}>
            {(fields, { add, remove }) => (
              <>
                <Row gutter={16} style={{ marginBottom: "10px" }}>
                  <Col
                    span={24}
                    style={{ display: "flex", alignItems: "center" }}
                  >
                    <h4 style={{ margin: 0, marginRight: 16 }}>Topics:</h4>
                    <div style={{ display: "flex", gap: "8px" }}>
                      <Button
                        type="dashed"
                        onClick={() => add()}
                        style={{
                          display: "flex",
                          alignItems: "center",
                          marginLeft: "2px",
                        }}
                        className="custom-button-hover"
                      >
                        <PlusCircleOutlined /> Add Topic
                      </Button>
                    </div>
                  </Col>
                </Row>
                {fields.map((field, index) => (
                  <Row gutter={16} key={field.key} style={{ marginBottom: 0 }}>
                    <Col
                      span={24}
                      style={{ display: "flex", alignItems: "center" }}
                    >
                      <Form.Item
                        {...field}
                        label={`Topic ${index + 1}:`}
                        name={[field.name, "topic"]}
                        fieldKey={[field.fieldKey, "topic"]}
                        rules={[
                          { required: true, message: "Topic is required" },
                        ]}
                        style={{ marginBottom: 6, flexGrow: 1 }}
                        labelCol={{ span: 2 }}
                        wrapperCol={{ span: 20 }}
                        labelAlign="left"
                      >
                        <Input
                          placeholder="Opc/OpcUaDriver/path/Objects/#/#/#"
                          style={{ marginLeft: 16 }}
                        />
                      </Form.Item>
                      <Button
                        type="dashed"
                        onClick={() => remove(field.name)}
                        disabled={fields.length <= 1}
                        className="custom-button-hover"
                        style={{
                          marginLeft: "8px",
                          display: "flex",
                          alignItems: "center",
                          marginBottom: 6,
                        }}
                      >
                        <MinusCircleOutlined /> Remove
                      </Button>
                    </Col>
                  </Row>
                ))}
              </>
            )}
          </Form.List>
          <Form.Item style={{ textAlign: "center", marginTop: 16 }}>
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
                style={{ backgroundColor: "#ffebcc" }}
              >
                <p>
                  <strong>Type:</strong> {item.type}
                </p>
                <p>
                  <strong>Status:</strong> {item.status}
                </p>
                <p>
                  <strong>Port:</strong> {JSON.parse(item.config).Port}
                </p>
                <p>
                  <strong>Log Level:</strong> {JSON.parse(item.config).LogLevel}
                </p>
                <p>
                  <strong>Format:</strong> {JSON.parse(item.config).Format}
                </p>
                <div style={{ display: "flex", justifyContent: "center" }}>
                  <Button
                    onClick={() => handleShowPopup(item)}
                    className="custom-button"
                  >
                    Show Logs
                  </Button>
                </div>
              </Card>
            </Col>
          ))
        ) : (
          <></>
        )}
      </Row>
      {isPopupVisible && selectedItem && (
        <ShowMoreLogsPopup
          visible={isPopupVisible}
          onClose={handleClosePopup}
          type={selectedItem.type}
          id={selectedItem.id}
        />
      )}
    </>
  );
}
