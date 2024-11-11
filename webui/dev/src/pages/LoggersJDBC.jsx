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
        enableComponent(type: JdbcLogger, id: "${id}") {
          ok
          code
          message
        }
      }
    `;
  } else if (key === "disable") {
    mutation = `
      mutation {
        disableComponent(type: JdbcLogger, id: "${id}") {
          ok
          code
          message
        }
      }
    `;
  } else if (key === "delete") {
    mutation = `
      mutation {
        deleteComponent(type: JdbcLogger, id: "${id}") {
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

export function LoggersJDBC() {
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
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [topicFields, setTopicFields] = useState([{ key: Math.random() }]);

  const fetchData = async () => {
    const query = `{
      getComponents(group: Logger, type: JdbcLogger) {
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
    setTitle("JDBC Loggers Configuration");
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

    let url;
    if (values.DBType === "SQL Express") {
      url = `jdbc:sqlserver://${values.Url};databaseName=${
        values.Database
      };encrypt=${values.Encryption ? "true" : "false"};`;
    } else {
      url = `jdbc:${values.DBType.toLowerCase()}://${values.Url}:${
        values.Port
      }/${values.Database}`;
    }

    const dataObject = {
      Id: values.Id,
      Enabled: values.Enabled || false,
      LogLevel: values.LogLevel || "",
      Url: url,
      Username: values.Username || "",
      Password: values.Password || "",
      Logging: loggingTopics,
    };

    const configString = JSON.stringify(dataObject);

    const query = `
      mutation {
        createComponent(type: JdbcLogger, config: "${configString.replace(
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
          name="jdbc_logger_form"
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
                label="Endpoint:"
                name="Url"
                rules={[
                  { required: true, message: "Please input a valid URL!" },
                ]}
                labelCol={{ span: 6 }}
                wrapperCol={{ span: 16 }}
              >
                <Input placeholder="192.168.0.123" />
              </Form.Item>
              <Form.Item
                label="Port:"
                name="Port"
                rules={[
                  {
                    required: true,
                    message: "Please input a valid port number!",
                  },
                ]}
                labelCol={{ span: 6 }}
                wrapperCol={{ span: 16 }}
              >
                <Input placeholder="5432" />
              </Form.Item>
              <Form.Item
                label="Database:"
                name="Database"
                labelCol={{ span: 6 }}
                wrapperCol={{ span: 16 }}
                rules={[
                  { required: true, message: "Please input a valid database!" },
                ]}
              >
                <Input placeholder="AG" />
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
                label="Username:"
                name="Username"
                labelCol={{ span: 6 }}
                wrapperCol={{ span: 16 }}
              >
                <Input placeholder="user" />
              </Form.Item>
              <Form.Item
                label="Password:"
                name="Password"
                labelCol={{ span: 6 }}
                wrapperCol={{ span: 16 }}
              >
                <Input.Password placeholder="password" />
              </Form.Item>
              <Form.Item
                label="Type:"
                name="DBType"
                initialValue="PostgreSQL"
                labelCol={{ span: 6 }}
                wrapperCol={{ span: 16 }}
              >
                <Select placeholder="Select DB Type">
                  <Option value="PostgreSQL">PostgreSQL</Option>
                  <Option value="SQL Express">SQL Express</Option>
                  <Option value="MS SQL" disabled>
                    MS SQL
                  </Option>
                  <Option value="MySQL" disabled>
                    MySQL
                  </Option>
                </Select>
              </Form.Item>
              <Form.Item
                label="Encryption:"
                name="Encryption"
                valuePropName="checked"
                labelCol={{ span: 6 }}
                wrapperCol={{ span: 16 }}
              >
                <Switch
                  checkedChildren={<CheckOutlined />}
                  unCheckedChildren={<CloseOutlined />}
                />
              </Form.Item>
            </Col>
          </Row>
          <Form.List name="loggingTopics" initialValue={[{ Topic: "" }]}>
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
                        name={[field.name, "Topic"]}
                        fieldKey={[field.fieldKey, "Topic"]}
                        rules={[
                          { required: true, message: "Topic is required" },
                        ]}
                        labelCol={{ span: 2 }}
                        wrapperCol={{ span: 20 }}
                        style={{ marginBottom: 6, flexGrow: 1 }}
                        labelAlign="left"
                      >
                        <Input
                          placeholder="Opc/OpcUaDriver/path/Objects/#/#/#"
                          style={{ marginLeft: 16 }}
                        />
                      </Form.Item>
                      <Button
                        type="dashed"
                        onClick={() => remove(fields.length - 1)}
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
                  <strong>Url:</strong>
                  <span className="url-text">
                    {JSON.parse(item.config).Url}
                  </span>
                </p>
                <p>
                  <strong>Database:</strong> {JSON.parse(item.config).Database}
                </p>
                <p>
                  <strong>Log Level:</strong> {JSON.parse(item.config).LogLevel}
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
