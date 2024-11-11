import React, { useEffect, useState } from "react";
import { usePageTitle } from "../utils/PageTitleContext";
import {
  CloseOutlined,
  CheckOutlined,
  PlusOutlined,
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
        enableComponent(type: GraphQLServer, id: "${id}") {
          ok
          code
          message
        }
      }
    `;
  } else if (key === "disable") {
    mutation = `
      mutation {
        disableComponent(type: GraphQLServer, id: "${id}") {
          ok
          code
          message
        }
      }
    `;
  } else if (key === "delete") {
    mutation = `
      mutation {
        deleteComponent(type: GraphQLServer, id: "${id}") {
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

export function ServersGQL() {
  const [isPopupVisible, setIsPopupVisible] = useState(false);
  const [selectedItem, setSelectedItem] = useState(null);
  const [form] = Form.useForm();
  const { setTitle } = usePageTitle();
  const auth = useAuth();
  const [apiData, setApiData] = useState([]);
  const [loading, setLoading] = useState(true);
  const [isFormVisible, setIsFormVisible] = useState(false);
  const [topicFields, setTopicFields] = useState([{ key: Math.random() }]);

  const fetchData = async () => {
    const endpoint = localStorage.getItem("serverEndpoint") || "localhost";
    const serverURL = `http://${endpoint}:9999`;
    const query = `{
      getComponents(group: Server, type: GraphQLServer) {
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
    setTitle("GraphQL Servers Configuration");
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

    const dataObject = {
      Id: values.Id,
      Enabled: values.Enabled || false,
      LogLevel: values.LogLevel || "INFO",
      Port: Number(values.Port) || 4000,
    };

    const configString = JSON.stringify(dataObject).replace(/"/g, '\\"');

    const query = `
      mutation {
        createComponent(type: GraphQLServer, config: "${configString}") {
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
          name="gql_form"
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
                <Input placeholder="4000" />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item style={{ textAlign: "center" }}>
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
                  <strong>Port:</strong> {JSON.parse(item.config).Host}:
                  {JSON.parse(item.config).Port}
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
